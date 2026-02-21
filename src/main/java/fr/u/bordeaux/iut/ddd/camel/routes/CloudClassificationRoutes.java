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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudClassificationRoutes extends EndpointRouteBuilder {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MinioClient minioClient;

    @Inject
    TestSseBridge testSseBridge;

    @ConfigProperty(name = "GENERAL_CLASSIFICATION_THRESHOLD", defaultValue = "0.5")
    double generalClassificationThreshold;

    @Override
    public void configure() {
        onException(Exception.class)
                .onWhen(exchangeProperty("cloudcatcher.outboxEventId").isNotNull())
                .handled(true)
                .process(new MarkOutboxEventFailure())
                .to(jpa(OutboxEvent.class.getCanonicalName()))
                .log("Classification dispatch retry failed for outboxId=${exchangeProperty.cloudcatcher.outboxEventId}: ${exception.message}");





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

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.general.classifier.results")
                .routingKey("cloud.general.classifier.results")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(new ParseGeneralClassificationResult())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c left join fetch c.generalClassificationDecision where c.id = :cloudId"))
                .process(new ApplyGeneralClassificationDecision())
                .choice()
                .when(exchangeProperty("cloudcatcher.persistGeneralClassificationDecision").isEqualTo(true))
                .process(exchange -> exchange.getMessage().setBody(exchange.getProperty("cloudcatcher.cloudEntity", Cloud.class)))
                .to(jpa(Cloud.class.getCanonicalName()))
                .process(new EmitCloudUpsertStreamEvent())
                .end()
                .choice()
                .when(exchangeProperty("cloudcatcher.forwardToCloudClassification").isEqualTo(true))
                .process(new BuildCloudClassificationRequestFromCloud())
                .setExchangePattern(ExchangePattern.InOnly)
                .to("spring-rabbitmq:cloud-classifier-exchange?routingKey=cloud.cloud-classifier.request&exchangeType=direct")
                .end();

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.cloud-classifier.results")
                .routingKey("cloud.cloud-classifier.results")
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

    private class PrepareGeneralClassificationRequestMessage implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.setProperty("cloudcatcher.forwardToGeneralClassification", false);
                return;
            }

            Cloud cloud = clouds.get(0);
            if (cloud.isPreventFurtherProcessing()) {
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

    private class ParseGeneralClassificationResult implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            JsonNode doc = objectMapper.readTree(exchange.getMessage().getBody(String.class));
            long cloudId = doc.path("cloudId").asLong();
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloudId);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
            exchange.setProperty("cloudcatcher.generalClassificationResult", doc);
        }
    }

    private class ApplyGeneralClassificationDecision implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.setProperty("cloudcatcher.forwardToCloudClassification", false);
                exchange.setProperty("cloudcatcher.persistGeneralClassificationDecision", false);
                return;
            }
            Cloud cloud = clouds.get(0);
            JsonNode result = exchange.getProperty("cloudcatcher.generalClassificationResult", JsonNode.class);
            boolean blocked = matchesGeneralClassificationThreshold(result, generalClassificationThreshold);
            cloud.setPreventFurtherProcessing(blocked);
            exchange.setProperty("cloudcatcher.cloudEntity", cloud);
            exchange.setProperty("cloudcatcher.persistGeneralClassificationDecision", blocked);
            exchange.setProperty("cloudcatcher.forwardToCloudClassification", !blocked);
            if (blocked) {
                String label = bestLabel(result);
                double score = bestScore(result);
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
                exchange.setProperty("cloudcatcher.generalClassificationDecisionEntity", decision);
            }
        }
    }

    private class BuildCloudClassificationRequestFromCloud implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getProperty("cloudcatcher.cloudEntity", Cloud.class);
            if (cloud == null) {
                exchange.setRouteStop(true);
                return;
            }
            exchange.getMessage().setBody(buildCloudClassificationRequestPayload(cloud));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setHeader("CamelSpringRabbitmqDeliveryMode", "PERSISTENT");
            exchange.getMessage().setHeader("messageId", "cloud-" + cloud.getId());
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
            if (cloud.isPreventFurtherProcessing()) {
                exchange.setRouteStop(true);
                return;
            }
            String cloudName = exchange.getProperty("cloudcatcher.documentCloudName", String.class);
            cloud.setCloudName(cloudName);
            exchange.getMessage().setBody(cloud);
        }
    }

    private String buildGeneralClassificationRequestPayload(Cloud cloud) throws Exception {
        String imageUrl = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket("bucket")
                        .object(cloud.getMinioObjectName())
                        .expiry(60 * 60)
                        .build()
        );
        Map<String, Object> request = new HashMap<>();
        request.put("documentType", "GeneralClassificationRequest");
        request.put("cloudId", cloud.getId());
        request.put("userName", cloud.getUser().getUserName());
        request.put("minioObjectName", cloud.getMinioObjectName());
        request.put("imageUrl", imageUrl);
        request.put("occurredAt", Instant.now().toString());
        return objectMapper.writeValueAsString(request);
    }

    private String buildCloudClassificationRequestPayload(Cloud cloud) throws Exception {
        String imageUrl = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket("bucket")
                        .object(cloud.getMinioObjectName())
                        .expiry(60 * 60)
                        .build()
        );
        Map<String, Object> request = new HashMap<>();
        request.put("documentType", "CloudClassificationRequest");
        request.put("cloudId", cloud.getId());
        request.put("userName", cloud.getUser().getUserName());
        request.put("minioObjectName", cloud.getMinioObjectName());
        request.put("imageUrl", imageUrl);
        request.put("occurredAt", Instant.now().toString());
        return objectMapper.writeValueAsString(request);
    }

    private static boolean matchesGeneralClassificationThreshold(JsonNode payload, double threshold) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        JsonNode classification = payload.path("classification");
        if (classification.isObject() && classification.path("score").asDouble(0.0) >= threshold) {
            return true;
        }
        if (arrayContainsScoreAtOrAbove(payload.path("predictions"), threshold)) {
            return true;
        }
        return arrayContainsScoreAtOrAbove(payload.path("detections"), threshold);
    }

    private static boolean arrayContainsScoreAtOrAbove(JsonNode array, double threshold) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (item.path("score").asDouble(0.0) >= threshold) {
                return true;
            }
        }
        return false;
    }

    private static String bestLabel(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "unknown";
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
}
