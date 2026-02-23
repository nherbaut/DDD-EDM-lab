package fr.u.bordeaux.iut.ddd.camel.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.u.bordeaux.iut.ddd.camel.processor.*;
import fr.u.bordeaux.iut.ddd.model.Cloud;
import fr.u.bordeaux.iut.ddd.model.GeneralClassificationDecision;
import fr.u.bordeaux.iut.ddd.model.OutboxEvent;
import fr.u.bordeaux.iut.ddd.model.User;
import fr.u.bordeaux.iut.ddd.resources.TestSseBridge;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Processor;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CloudClassificationRoutes extends EndpointRouteBuilder {
    private static final Logger LOG = Logger.getLogger(CloudClassificationRoutes.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MinioClient minioClient;

    @Inject
    TestSseBridge testSseBridge;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-endpoint", defaultValue = "http://minio:9000")
    String classifierMinioEndpoint;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-access-key", defaultValue = "adminadmin")
    String classifierMinioAccessKey;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-secret-key", defaultValue = "adminadmin")
    String classifierMinioSecretKey;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-region", defaultValue = "us-east-1")
    String classifierMinioRegion;

    private volatile MinioClient classifierMinioClient;

    @Override
    public void configure() {
        onException(Exception.class)
                .onWhen(exchangeProperty("cloudcatcher.outboxEventId").isNotNull())
                .handled(true)
                .process(new MarkOutboxEventFailure())
                .to(jpa(OutboxEvent.class.getCanonicalName()))
                .process(exchange -> {
                    Object outboxId = exchange.getProperty("cloudcatcher.outboxEventId");
                    Throwable exception = exchange.getException();
                    LOG.errorf("Classification dispatch retry failed for outboxId=%s: %s",
                            outboxId, exception == null ? "unknown" : exception.getMessage());
                });





        from(timer("cloud-classification-dispatch").period(60000).delay(60000))
                .to(jpa(OutboxEvent.class.getCanonicalName()).query("select e from OutboxEvent e where e.status = 'PENDING' order by e.createdAt"))
                .split(body())
                .to(direct("dispatch-classification-outbox-event"));

        from(direct("dispatch-classification-outbox-event"))
                .process(new PrepareOutboxContext())
                .process(new SetCloudLookupJpaParameters())
                .to(jpa(Cloud.class.getCanonicalName() + "?query=select c from Cloud c where c.id = :cloudId"))
                .process(new PrepareGeneralClassificationRequestMessage())
                .choice()
                .when(exchangeProperty("cloudcatcher.forwardToGeneralClassification").isEqualTo(true))
                .setExchangePattern(ExchangePattern.InOnly)
                .to("spring-rabbitmq:cloud-classifier-exchange?routingKey=cloud.general.classifier.request&exchangeType=direct")
                .end()
                .process(new MarkOutboxEventSent())
                .to(jpa(OutboxEvent.class.getCanonicalName()));

        from(platformHttp("/clouds/{cloudId}/retry").httpMethodRestrict("POST"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .process(new SetCloudRetryLookupJpaParameters())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c left join fetch c.generalClassificationDecision where c.id = :cloudId and c.user.userName = :userName and c.deletionRequested = false"))
                .process(new BuildRetryClassificationOutboxEvent())
                .choice()
                .when(exchangeProperty("cloudcatcher.retryPayload").isNull())
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Cloud not found\n"))
                .otherwise()
                .process(exchange -> exchange.getMessage().setBody(exchange.getProperty("cloudcatcher.retryPayload", String.class)))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setHeader("CamelSpringRabbitmqDeliveryMode", constant("PERSISTENT"))
                .setHeader("messageId", simple("general-retry-${header.cloudId}-${date:now:yyyyMMddHHmmssSSS}"))
                .setExchangePattern(ExchangePattern.InOnly)
                .to("spring-rabbitmq:cloud-classifier-exchange?routingKey=cloud.general.classifier.request&exchangeType=direct")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Classification retry accepted\n"))
                .end();

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.catcher.general.denied.events")
                .routingKey("classification.general.denied.v1")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(new ParseGeneralClassificationDeniedEvent())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c left join fetch c.generalClassificationDecision where c.id = :cloudId"))
                .process(new ApplyGeneralClassificationDeniedDecision())
                .process(exchange -> exchange.getMessage().setBody(exchange.getProperty("cloudcatcher.cloudEntity", Cloud.class)))
                .to(jpa(Cloud.class.getCanonicalName()))
                .process(new EmitCloudUpsertStreamEvent());

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.catcher.cloud.completed.events")
                .routingKey("classification.cloud.completed.v1")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(new ApplyCloudClassificationResult())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c where c.id = :cloudId"))
                .process(new UpdateCloudNameFromClassificationResult())
                .to(jpa(Cloud.class.getCanonicalName()))
                .process(new EmitCloudUpsertStreamEvent());
    }



    private static class PrepareOutboxContext implements Processor {
        @Override
        public void process(Exchange exchange) {
            OutboxEvent event = exchange.getMessage().getBody(OutboxEvent.class);
            exchange.setProperty("cloudcatcher.outboxEvent", event);
            exchange.setProperty("cloudcatcher.outboxEventId", event == null ? null : event.getId());
            String eventType = event == null ? null : event.getEventType();
            exchange.setProperty("cloudcatcher.outboxEventType", eventType);
            exchange.setProperty("cloudcatcher.forwardToGeneralClassification", true);
        }
    }

    private static class SetCloudLookupJpaParameters implements Processor {
        @Override
        public void process(Exchange exchange) {
            OutboxEvent event = exchange.getProperty("cloudcatcher.outboxEvent", OutboxEvent.class);
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", event == null ? null : event.getAggregateId());
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
        }
    }

    private static class SetCloudRetryLookupJpaParameters implements Processor {
        @Override
        public void process(Exchange exchange) {
            Long cloudId = exchange.getMessage().getHeader("cloudId", Long.class);
            String userName = exchange.getProperty("cloudcatcher.userName", String.class);
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloudId);
            jpaParams.put("userName", userName);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
        }
    }

    private class PrepareGeneralClassificationRequestMessage implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.setProperty("cloudcatcher.forwardToGeneralClassification", false);
                return;
            }

            Cloud cloud = clouds.get(0);
            String eventType = exchange.getProperty("cloudcatcher.outboxEventType", String.class);
            boolean retryEvent = "CloudClassificationRetryRequested".equals(eventType);
            if (cloud.isPreventFurtherProcessing() && !retryEvent) {
                exchange.setProperty("cloudcatcher.forwardToGeneralClassification", false);
                return;
            }
            exchange.setProperty("cloudcatcher.forwardToGeneralClassification", true);
            exchange.getMessage().setBody(buildGeneralClassificationRequestPayload(cloud));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setHeader("CamelSpringRabbitmqDeliveryMode", "PERSISTENT");
            exchange.getMessage().setHeader("messageId", "general-" + cloud.getId());
        }
    }

    private class BuildRetryClassificationOutboxEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            exchange.setProperty("cloudcatcher.retryPayload", null);
            if (clouds == null || clouds.isEmpty()) {
                return;
            }
            Cloud cloud = clouds.get(0);
            if (cloud.getCloudName() != null && !cloud.getCloudName().isBlank()) {
                return;
            }
            boolean quotaDenied = isQuotaDenied(cloud);
            if (cloud.isPreventFurtherProcessing() && !quotaDenied) {
                return;
            }
            String payload = buildGeneralClassificationRequestPayload(cloud);
            exchange.setProperty("cloudcatcher.retryPayload", payload);
        }
    }

    private static boolean isQuotaDenied(Cloud cloud) {
        if (cloud == null || cloud.getGeneralClassificationDecision() == null) {
            return false;
        }
        String label = cloud.getGeneralClassificationDecision().getLabel();
        if (label == null) {
            return false;
        }
        String normalized = label.toUpperCase();
        return normalized.contains("QUOTA");
    }

    private class ParseGeneralClassificationDeniedEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            JsonNode doc = objectMapper.readTree(exchange.getMessage().getBody(String.class));
            long cloudId = doc.path("cloudId").asLong(-1L);
            if (cloudId < 0) {
                exchange.setRouteStop(true);
                return;
            }
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloudId);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
            exchange.setProperty("cloudcatcher.generalClassificationDeniedEvent", doc);
        }
    }

    private class ApplyGeneralClassificationDeniedDecision implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.setRouteStop(true);
                return;
            }
            Cloud cloud = clouds.get(0);
            JsonNode deniedEvent = exchange.getProperty("cloudcatcher.generalClassificationDeniedEvent", JsonNode.class);
            cloud.setPreventFurtherProcessing(true);
            exchange.setProperty("cloudcatcher.cloudEntity", cloud);
            String label = bestLabel(deniedEvent);
            double score = bestScore(deniedEvent);
            GeneralClassificationDecision decision = cloud.getGeneralClassificationDecision();
            if (decision == null) {
                decision = new GeneralClassificationDecision(cloud, label, score);
            } else {
                decision.setLabel(label);
                decision.setScore(score);
                decision.setDetectedAt(Instant.now());
                decision.setCloud(cloud);
            }
            cloud.setGeneralClassificationDecision(decision);
        }
    }

    private class ApplyCloudClassificationResult implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            String body = exchange.getMessage().getBody(String.class);
            JsonNode doc = objectMapper.readTree(body);
            long cloudId = doc.get("cloudId").asLong();
            String cloudName = doc.get("cloudName").asText();
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloudId);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
            exchange.setProperty("cloudcatcher.documentCloudName", cloudName);
        }
    }

    private static class UpdateCloudNameFromClassificationResult implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.setRouteStop(true);
                return;
            }
            Cloud cloud = clouds.get(0);
            String cloudName = exchange.getProperty("cloudcatcher.documentCloudName", String.class);
            cloud.setCloudName(cloudName);
            cloud.setPreventFurtherProcessing(false);
            exchange.getMessage().setBody(cloud);
        }
    }

    private String buildGeneralClassificationRequestPayload(Cloud cloud) throws Exception {
        String imageUrl = buildClassifierPresignedUrl(cloud.getMinioObjectName());
        Map<String, Object> request = new HashMap<>();
        request.put("documentType", "GeneralClassificationRequest");
        request.put("requestId", UUID.randomUUID().toString());
        request.put("cloudId", cloud.getId());
        request.put("userName", cloud.getUser().getUserName());
        request.put("minioObjectName", cloud.getMinioObjectName());
        request.put("imageUrl", imageUrl);
        request.put("occurredAt", Instant.now().toString());
        return objectMapper.writeValueAsString(request);
    }

    private static String bestLabel(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "unknown";
        }
        JsonNode reason = payload.path("reason");
        if (reason.isTextual() && !reason.asText("").isBlank()) {
            return reason.asText("unknown");
        }
        JsonNode classification = payload.path("classification");
        if (classification.isObject() && classification.path("label").isTextual()) {
            return classification.path("label").asText("unknown");
        }
        JsonNode predictions = payload.path("predictions");
        if (predictions.isArray() && predictions.size() > 0 && predictions.get(0).path("label").isTextual()) {
            return predictions.get(0).path("label").asText("unknown");
        }
        JsonNode detections = payload.path("detections");
        if (detections.isArray() && detections.size() > 0 && detections.get(0).path("label").isTextual()) {
            return detections.get(0).path("label").asText("unknown");
        }
        return "unknown";
    }

    private static double bestScore(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return 0.0;
        }
        JsonNode classification = payload.path("classification");
        if (classification.isObject()) {
            return classification.path("score").asDouble(0.0);
        }
        JsonNode predictions = payload.path("predictions");
        if (predictions.isArray() && predictions.size() > 0) {
            return predictions.get(0).path("score").asDouble(0.0);
        }
        JsonNode detections = payload.path("detections");
        if (detections.isArray() && detections.size() > 0) {
            return detections.get(0).path("score").asDouble(0.0);
        }
        return 0.0;
    }

    private static class MarkOutboxEventSent implements Processor {
        @Override
        public void process(Exchange exchange) {
            OutboxEvent event = exchange.getProperty("cloudcatcher.outboxEvent", OutboxEvent.class);
            if (event == null) {
                return;
            }
            event.markSent();
            exchange.getMessage().setBody(event);
        }
    }

    private static class MarkOutboxEventFailure implements Processor {
        @Override
        public void process(Exchange exchange) {
            OutboxEvent event = exchange.getProperty("cloudcatcher.outboxEvent", OutboxEvent.class);
            if (event == null) {
                return;
            }
            Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
            String message = failure == null ? "unknown error" : failure.getMessage();
            event.markFailure(message);
            exchange.getMessage().setBody(event);
        }
    }

    private class EmitCloudUpsertStreamEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            if (cloud == null || cloud.getUser() == null) {
                return;
            }
            Map<String, Object> cloudItem = new HashMap<>();
            cloudItem.put("id", cloud.getId());
            cloudItem.put("cloudName", cloud.getCloudName());
            cloudItem.put("preventFurtherProcessing", cloud.isPreventFurtherProcessing());
            if (cloud.getGeneralClassificationDecision() != null) {
                cloudItem.put("deniedByLabel", cloud.getGeneralClassificationDecision().getLabel());
                cloudItem.put("deniedByScore", cloud.getGeneralClassificationDecision().getScore());
            }
            String[] target = extractDownloadTargetFromMinioObjectName(cloud.getMinioObjectName());
            if (target != null) {
                cloudItem.put("downloadUserId", target[0]);
                cloudItem.put("downloadFileName", target[1]);
            }
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "cloud-upserted",
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

    private String buildClassifierPresignedUrl(String minioObjectName) throws Exception {
        return classifierMinioClient().getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket("bucket")
                        .object(minioObjectName)
                        .region(classifierMinioRegion)
                        .expiry(60 * 60)
                        .build()
        );
    }

    private MinioClient classifierMinioClient() {
        MinioClient existing = classifierMinioClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (classifierMinioClient == null) {
                classifierMinioClient = MinioClient.builder()
                        .endpoint(classifierMinioEndpoint)
                        .credentials(classifierMinioAccessKey, classifierMinioSecretKey)
                        .build();
            }
            return classifierMinioClient;
        }
    }
}
