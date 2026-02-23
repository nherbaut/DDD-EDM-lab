package fr.u.bordeaux.iut.ddd.camel.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.u.bordeaux.iut.ddd.conf.AccountantProjectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.jboss.logging.Logger;

import java.time.Instant;

@ApplicationScoped
public class AccountantProjectionRoutes extends EndpointRouteBuilder {

    private static final Logger LOG = Logger.getLogger(AccountantProjectionRoutes.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AccountantProjectionService accountantProjectionService;

    @Override
    public void configure() {
        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.catcher.quota.purchased.events")
                .routingKey("accounting.quota.purchased.v1")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(exchange -> {
                    JsonNode document = objectMapper.readTree(exchange.getMessage().getBody(String.class));
                    String userName = document.path("userName").asText("");
                    int purchasedQuota = document.path("purchasedQuota").asInt(-1);
                    int amountCents = document.path("amountCents").asInt(-1);
                    String occurredAtValue = document.path("occurredAt").asText("");
                    if (userName.isBlank() || purchasedQuota <= 0 || amountCents < 0) {
                        LOG.warnf("Ignoring malformed quota-purchased event: %s", exchange.getMessage().getBody(String.class));
                        return;
                    }
                    Instant occurredAt;
                    try {
                        occurredAt = occurredAtValue.isBlank() ? Instant.now() : Instant.parse(occurredAtValue);
                    } catch (Exception ignored) {
                        occurredAt = Instant.now();
                    }
                    accountantProjectionService.recordPurchase(userName, purchasedQuota, amountCents, occurredAt);
                    LOG.infof("Quota purchase projection recorded user=%s purchasedQuota=%d amountCents=%d",
                            userName, purchasedQuota, amountCents);
                });
    }
}
