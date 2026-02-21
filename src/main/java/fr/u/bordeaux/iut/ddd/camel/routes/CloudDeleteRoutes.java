package fr.u.bordeaux.iut.ddd.camel.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.u.bordeaux.iut.ddd.camel.processor.HardDeleteCloudEntityProcessor;
import fr.u.bordeaux.iut.ddd.camel.processor.PopulateCloudLookupJpaParams;
import fr.u.bordeaux.iut.ddd.camel.processor.PrepareHardDeleteForSoftDeletedCloudProcessor;

import fr.u.bordeaux.iut.ddd.camel.processor.RoleCheckProcessor;
import fr.u.bordeaux.iut.ddd.model.Cloud;
import fr.u.bordeaux.iut.ddd.resources.TestSseBridge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Processor;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.minio.MinioConstants;
import org.apache.camel.model.dataformat.JsonLibrary;

import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudDeleteRoutes extends EndpointRouteBuilder {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TestSseBridge testSseBridge;

    @Inject
    HardDeleteCloudEntityProcessor hardDeleteCloudEntityProcessor;

    @Override
    public void configure() {
        from(platformHttp("/clouds/delete").httpMethodRestrict("POST"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .unmarshal().json(JsonLibrary.Jsonb, Map.class)
                .process(new ExtractDeleteCloudIds())
                .process(new GuardEmptyCloudsIds())
                .process(new BuildDeleteCloudJpaParams())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c where c.id in :ids and c.user.userName = :userName"))
                .process(new GuardCloudOwnership())
                .split(body())
                .process(new PrepareCloudSoftDeleteAndHeaders())
                .to(direct("mark-cloud-delete-requested"))
                .process(new BuildCloudDeleteRequestMessage())
                .setExchangePattern(ExchangePattern.InOnly)
                .to(springRabbitmq("cloud-classifier-exchange")
                        .queues("cloud.delete.requests")
                        .routingKey("cloud.delete.request")
                )
                .end()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Deletion requested\n"));

        from(direct("mark-cloud-delete-requested")).to(jpa(Cloud.class.getCanonicalName()));

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.delete.requests")
                .routingKey("cloud.delete.request")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(new ParseCloudDeleteRequestMessage())
                .log("minio deletion requested: ${body}")
                .to(minio("bucket").operation("deleteObject"));

        from(direct("handle-minio-object-removed"))
                .process(new PopulateCloudLookupJpaParams())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c where c.minioObjectName = :minioObjectName"))
                .process(new PrepareHardDeleteForSoftDeletedCloudProcessor())
                .log("minio remove event: hardDelete=${exchangeProperty.cloudcatcher.hardDeleteCloud}")
                .process(new EmitCloudDeletedStreamEvent())
                .process(hardDeleteCloudEntityProcessor)
                .log("hard-deleted cloud row for key=${exchangeProperty.cloudcatcher.minioObjectName}")
                .end();

    }

    private static class ExtractDeleteCloudIds implements Processor {
        @Override
        public void process(Exchange exchange) {
            Map<String, Object> root = exchange.getMessage().getBody(Map.class);
            Object rawIds = root == null ? null : root.get("cloudIds");
            List<Long> ids = new java.util.ArrayList<>();
            if (rawIds instanceof List<?> values) {
                for (Object value : values) {
                    if (value instanceof Number number) {
                        ids.add(number.longValue());
                    } else if (value instanceof String text && !text.isBlank()) {
                        ids.add(Long.parseLong(text));
                    }
                }
            }
            exchange.setProperty("cloudcatcher.deleteCloudIds", ids);
        }
    }

    private static class GuardEmptyCloudsIds implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Long> cloudIds = exchange.getProperty("cloudcatcher.deleteCloudIds", List.class);
            if (cloudIds == null || cloudIds.isEmpty()) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain");
                exchange.getMessage().setBody("cloudIds is required\n");
                exchange.setRouteStop(true);
            }
        }
    }

    private static class BuildDeleteCloudJpaParams implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Long> cloudIds = exchange.getProperty("cloudcatcher.deleteCloudIds", List.class);
            String userName = exchange.getProperty("cloudcatcher.userName", String.class);
            Map<String, Object> params = new HashMap<>();
            params.put("ids", cloudIds);
            params.put("userName", userName);
            exchange.getMessage().setHeader("CamelJpaParameters", params);
        }
    }

    private static class GuardCloudOwnership implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Long> requestedIds = exchange.getProperty("cloudcatcher.deleteCloudIds", List.class);
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || requestedIds == null || clouds.size() != requestedIds.size()) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 403);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain");
                exchange.getMessage().setBody("Forbidden\n");
                exchange.setRouteStop(true);
            }
        }
    }

    private static class PrepareCloudSoftDeleteAndHeaders implements Processor {
        @Override
        public void process(Exchange exchange) {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            cloud.setDeletionRequested(true);
            exchange.getMessage().setHeader(MinioConstants.OBJECT_NAME, cloud.getMinioObjectName());
            exchange.getMessage().setBody(cloud);
        }
    }

    private class BuildCloudDeleteRequestMessage implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            if (cloud == null) {
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("cloudId", cloud.getId());
            payload.put("minioObjectName", cloud.getMinioObjectName());
            exchange.getMessage().setBody(objectMapper.writeValueAsString(payload));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setHeader("CamelSpringRabbitmqDeliveryMode", "PERSISTENT");
        }
    }

    private class ParseCloudDeleteRequestMessage implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            String body = exchange.getMessage().getBody(String.class);
            if (body == null || body.isBlank()) {
                exchange.setRouteStop(true);
                return;
            }
            JsonNode document = objectMapper.readTree(body);
            String objectName = document.path("minioObjectName").asText("");
            if (objectName.isBlank()) {
                exchange.setRouteStop(true);
                return;
            }
            exchange.getMessage().setHeader(MinioConstants.OBJECT_NAME, objectName);
        }
    }

    private class EmitCloudDeletedStreamEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getProperty("cloudcatcher.cloudToHardDelete", Cloud.class);
            if (cloud == null || cloud.getUser() == null) {
                return;
            }
            Map<String, Object> cloudItem = new HashMap<>();
            cloudItem.put("id", cloud.getId());
            String[] target = extractDownloadTargetFromMinioObjectName(cloud.getMinioObjectName());
            if (target != null) {
                cloudItem.put("downloadUserId", target[0]);
                cloudItem.put("downloadFileName", target[1]);
            }
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "cloud-deleted",
                    "cloud", cloudItem,
                    "timestamp", Instant.now().toString()
            ));
            testSseBridge.emitIfConnected(cloud.getUser().getUserName(), payload);
        }
    }

    private static String[] extractDownloadTargetFromMinioObjectName(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return null;
        }
        String[] parts = objectName.split("/");
        if (parts.length >= 4 && "uploads".equals(parts[0]) && "clouds".equals(parts[1])) {
            String userId = parts[2];
            String fileName = String.join("/", java.util.Arrays.copyOfRange(parts, 3, parts.length));
            return new String[]{userId, fileName};
        }
        if (parts.length >= 3 && "cloud".equals(parts[0])) {
            String userId = parts[1];
            String fileName = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            return new String[]{userId, fileName};
        }
        return null;
    }
}
