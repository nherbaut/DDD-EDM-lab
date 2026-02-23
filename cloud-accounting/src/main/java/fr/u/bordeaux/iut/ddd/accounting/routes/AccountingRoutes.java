package fr.u.bordeaux.iut.ddd.accounting.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.u.bordeaux.iut.ddd.accounting.model.AccountingUser;
import fr.u.bordeaux.iut.ddd.accounting.service.QuotaService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AccountingRoutes extends EndpointRouteBuilder {

    private static final Logger LOG = Logger.getLogger(AccountingRoutes.class);

    @Inject
    QuotaService quotaService;

    @Inject
    ProducerTemplate producerTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void configure() {

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.accounting.user.created.events")
                .routingKey("admin.user.new")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(exchange -> {
                    JsonNode document = parseJson(exchange.getMessage().getBody(String.class));
                    String userName = document.path("userName").asText("");
                    LOG.infof("Received event routingKey=admin.user.new userName=%s payload=%s",
                            userName, exchange.getMessage().getBody(String.class));
                    if (userName.isBlank()) {
                        LOG.warnf("Ignoring malformed user-created event: %s", exchange.getMessage().getBody(String.class));
                        return;
                    }
                    AccountingUser user = quotaService.allocateWelcomeQuotaIfNew(userName);
                    LOG.infof("Accounting user upsert user=%s quota=%d", user.getUserName(), user.getQuota());
                    emitQuotaUpdated(user.getUserName(), user.getQuota(), "WELCOME");
                });


        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.accounting.general.classifier.request.events")
                .routingKey("cloud.general.classifier.request")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(exchange -> {
                    JsonNode document = parseJson(exchange.getMessage().getBody(String.class));
                    long cloudId = document.path("cloudId").asLong(-1L);
                    String userName = document.path("userName").asText(null);
                    String requestId = document.path("requestId").asText(null);
                    LOG.infof("Received event routingKey=cloud.general.classifier.request cloudId=%d userName=%s payload=%s",
                            cloudId, userName, exchange.getMessage().getBody(String.class));
                    if (cloudId < 0 || userName == null || userName.isBlank()) {
                        LOG.warnf("Ignoring malformed general request event: %s", exchange.getMessage().getBody(String.class));
                        exchange.setRouteStop(true);
                        return;
                    }
                    if (requestId == null || requestId.isBlank()) {
                        requestId = UUID.randomUUID().toString();
                    }
                    QuotaService.ReservationDecision decision = quotaService.reserveQuotaForRequest(requestId, cloudId, userName);

                    Map<String, Object> event = new HashMap<>();
                    event.put("requestId", requestId);
                    event.put("cloudId", cloudId);
                    event.put("userName", userName);
                    event.put("minioObjectName", document.path("minioObjectName").asText(null));
                    event.put("imageUrl", document.path("imageUrl").asText(null));
                    event.put("occurredAt", document.path("occurredAt").asText(Instant.now().toString()));
                    event.put("remainingQuota", decision.remainingQuota());

                    if (decision.reserved()) {
                        event.put("documentType", "ClassificationQuotaReservedV1");
                        exchange.getMessage().setHeader("cloudcatcher.routingKey", "classification.quota.reserved.v1");
                        LOG.infof("Quota reserved requestId=%s user=%s cloudId=%d remainingQuota=%d",
                                requestId, userName, cloudId, decision.remainingQuota());
                    } else {
                        event.put("documentType", "ClassificationDeniedByQuotaV1");
                        event.put("reason", "QUOTA_EXCEEDED");
                        exchange.getMessage().setHeader("cloudcatcher.routingKey", "classification.general.denied.v1");
                        LOG.infof("Quota rejected requestId=%s user=%s cloudId=%d remainingQuota=%d",
                                requestId, userName, cloudId, decision.remainingQuota());
                    }

                    exchange.getMessage().setBody(objectMapper.writeValueAsString(event));
                    exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                    exchange.getMessage().setHeader("CamelSpringRabbitmqDeliveryMode", "PERSISTENT");
                    emitQuotaUpdated(userName, decision.remainingQuota(), decision.reserved() ? "RESERVED" : "REJECTED");
                })
                .setExchangePattern(ExchangePattern.InOnly)
                .toD("spring-rabbitmq:cloud-classifier-exchange?routingKey=${header.cloudcatcher.routingKey}&exchangeType=direct");

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.accounting.quota.request.events")
                .routingKey("accounting.quota.request.v1")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(exchange -> {
                    JsonNode document = parseJson(exchange.getMessage().getBody(String.class));
                    String userName = document.path("userName").asText("");
                    int requestedPackageQuota = document.path("packageQuota").asInt(10);
                    int packageQuota = quotaService.normalizedPackageQuota(requestedPackageQuota);
                    LOG.infof("Received event routingKey=accounting.quota.request.v1 userName=%s packageQuota=%d payload=%s",
                            userName, packageQuota, exchange.getMessage().getBody(String.class));
                    if (userName.isBlank()) {
                        LOG.warnf("Ignoring malformed quota request event: %s", exchange.getMessage().getBody(String.class));
                        return;
                    }
                    AccountingUser user = quotaService.buyPackage(userName, packageQuota);
                    LOG.infof("Quota purchase applied user=%s added=%d newQuota=%d", userName, packageQuota, user.getQuota());
                    emitQuotaUpdated(userName, user.getQuota(), "PURCHASE");
                    emitQuotaPurchased(userName, packageQuota);
                });

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.accounting.cloud.classifier.completed.events")
                .routingKey("classification.cloud.completed.v1")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(exchange -> {
                    JsonNode document = parseJson(exchange.getMessage().getBody(String.class));
                    long cloudId = document.path("cloudId").asLong(-1L);
                    String userName = document.path("userName").asText(null);
                    String requestId = document.path("requestId").asText(null);
                    LOG.infof("Received event routingKey=classification.cloud.completed.v1 cloudId=%d userName=%s payload=%s",
                            cloudId, userName, exchange.getMessage().getBody(String.class));
                    if (cloudId < 0) {
                        LOG.warnf("Ignoring malformed cloud completed event: %s", exchange.getMessage().getBody(String.class));
                        return;
                    }
                    quotaService.markReservationCompleted(requestId, cloudId, userName);
                    LOG.infof("Quota reservation completed requestId=%s user=%s cloudId=%d", requestId, userName, cloudId);
                });
    }

    private JsonNode parseJson(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    private void emitQuotaUpdated(String userName, int remainingQuota, String cause) throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("documentType", "AccountingQuotaUpdatedV1");
        event.put("userName", userName);
        event.put("remainingQuota", remainingQuota);
        event.put("cause", cause);
        event.put("occurredAt", Instant.now().toString());
        producerTemplate.sendBodyAndHeaders(
                "spring-rabbitmq:cloud-classifier-exchange?routingKey=accounting.quota.updated.v1&exchangeType=direct",
                objectMapper.writeValueAsString(event),
                Map.of(
                        Exchange.CONTENT_TYPE, "application/json",
                        "CamelSpringRabbitmqDeliveryMode", "PERSISTENT"
                )
        );
    }

    private void emitQuotaPurchased(String userName, int purchasedQuota) throws Exception {
        int amountCents = switch (purchasedQuota) {
            case 1 -> 100;
            case 10 -> 500;
            case 30 -> 1500;
            default -> 500;
        };
        Map<String, Object> event = new HashMap<>();
        event.put("documentType", "AccountingQuotaPurchasedV1");
        event.put("userName", userName);
        event.put("purchasedQuota", purchasedQuota);
        event.put("amountCents", amountCents);
        event.put("occurredAt", Instant.now().toString());
        producerTemplate.sendBodyAndHeaders(
                "spring-rabbitmq:cloud-classifier-exchange?routingKey=accounting.quota.purchased.v1&exchangeType=direct",
                objectMapper.writeValueAsString(event),
                Map.of(
                        Exchange.CONTENT_TYPE, "application/json",
                        "CamelSpringRabbitmqDeliveryMode", "PERSISTENT"
                )
        );
    }
}
