package fr.u.bordeaux.iut.ddd.camel.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.u.bordeaux.iut.ddd.conf.QuotaProjectionService;
import fr.u.bordeaux.iut.ddd.resources.TestSseBridge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class QuotaProjectionRoutes extends EndpointRouteBuilder {

    private static final Logger LOG = Logger.getLogger(QuotaProjectionRoutes.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    QuotaProjectionService quotaProjectionService;

    @Inject
    TestSseBridge testSseBridge;

    @Override
    public void configure() {
        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.catcher.quota.updated.events")
                .routingKey("accounting.quota.updated.v1")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(exchange -> {
                    JsonNode document = objectMapper.readTree(exchange.getMessage().getBody(String.class));
                    String userName = document.path("userName").asText("");
                    int remainingQuota = document.path("remainingQuota").asInt(-1);
                    if (userName.isBlank() || remainingQuota < 0) {
                        LOG.warnf("Ignoring malformed quota-updated event: %s", exchange.getMessage().getBody(String.class));
                        return;
                    }
                    quotaProjectionService.upsert(userName, remainingQuota);
                    Map<String, Object> event = new HashMap<>();
                    event.put("type", "quota-updated");
                    event.put("userName", userName);
                    event.put("remainingQuota", remainingQuota);
                    testSseBridge.emit(userName, objectMapper.writeValueAsString(event));
                    LOG.infof("Quota projection updated user=%s remainingQuota=%d", userName, remainingQuota);
                });
    }
}
