package fr.u.bordeaux.iut.ddd.camel.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.u.bordeaux.iut.ddd.Populate;

import fr.u.bordeaux.iut.ddd.camel.processor.*;
import fr.u.bordeaux.iut.ddd.model.Cloud;
import fr.u.bordeaux.iut.ddd.model.OutboxEvent;
import fr.u.bordeaux.iut.ddd.model.User;
import fr.u.bordeaux.iut.ddd.resources.TestSseBridge;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.minio.MinioConstants;
import org.apache.camel.component.minio.MinioOperations;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.minio.http.Method;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudCreateRoutes extends EndpointRouteBuilder {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TestSseBridge testSseBridge;

    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "cloudcatcher.classifier-fetch-token", defaultValue = "dev-classifier-token")
    String classifierFetchToken;

    @Override
    public void configure() {

        from(platformHttp("/clouds").httpMethodRestrict("POST"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .process(GuardContentType.gardMime(MimeType.IMAGE))
                .process(new PopulateMinioHeaders())
                .log("Received /clouds upload")
                .to(minio("bucket").autoCreateBucket(true))
                .to(direct("cloud-insert-new-cloud-db"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Upload accepted\n"));

        from(direct("cloud-insert-new-cloud-db"))
                .process(new PopulateJpaParametersForUserProcessor())
                .to(jpa(User.class.getCanonicalName()).query("select u from User u where u.userName = :userName"))
                .process(new TestForNewUserProcessor())
                .choice()
                .when(exchangeProperty("cloudcatcher.newUser").isEqualTo(true))
                .to(jpa(User.class.getCanonicalName()).usePersist(true))
                .end()
                .process(new CreateNewCloudEntityProcessor())
                .to(jpa(Cloud.class.getCanonicalName()).usePersist(true))
                .process(new CreateClassificationOutboxEvent())
                .to(jpa(OutboxEvent.class.getCanonicalName()).usePersist(true))
                .to(direct("dispatch-classification-outbox-event"));

        from(seda("upload-request"))
                .setHeader(MinioConstants.OBJECT_NAME, simple("uploads/clouds/${header.userName}/${bean:type:java.util.UUID?method=randomUUID}.${header.fileExtension}"))
                .to(minio("bucket").autoCreateBucket(true).operation(MinioOperations.createUploadLink))
                .process(exchange -> {
                    String correlationId = exchange.getMessage().getHeader("correlationId", String.class);
                    String payload = exchange.getMessage().getBody(String.class);
                    testSseBridge.emit(correlationId, objectMapper.writeValueAsString(Map.of(
                            "type", "upload-link",
                            "correlationId", correlationId,
                            "payload", payload,
                            "timestamp", Instant.now().toString()
                    )));
                    testSseBridge.complete(correlationId);
                });

        from(platformHttp("/clouds").httpMethodRestrict("GET"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .process(Populate.from(
                        Populate.exchange(Populate.property("cloudcatcher.userName", String.class))
                                .to(Populate.message(Populate.header("CamelJpaParameters")), Populate.map("userName"))
                ))
                .to(direct("handle-clouds-retrieval"));

        from(platformHttp("/cloud/user/{userName}").httpMethodRestrict("GET"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .process(Populate.from(
                        Populate.exchange(Populate.headerValue("userName", String.class))
                                .to(Populate.message(Populate.header("CamelJpaParameters")), Populate.map("userName"))
                ))
                .to(direct("handle-clouds-retrieval"));

        from(direct("handle-clouds-retrieval"))
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c left join fetch c.generalClassificationDecision where c.user.userName = :userName and c.deletionRequested = false"))
                .process(new PopulateCloudsItems())
                .marshal().json(JsonLibrary.Jsonb)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from(platformHttp("/cloud/{userid}/{fileName}")
                .httpMethodRestrict("GET"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .setHeader(MinioConstants.OBJECT_NAME, simple("uploads/clouds/${header.userid}/${header.fileName}"))
                .to(minio("bucket").operation(MinioOperations.createDownloadLink))
                .setHeader("Location", bodyAs(String.class))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(307))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Redirecting to object storage\n"));

        from(platformHttp("/clouddb/{cloudId}").httpMethodRestrict("GET"))
                .choice()
                .when(header("token").isNotEqualTo(constant(classifierFetchToken)))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(403))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Forbidden\n"))
                .stop()
                .end()
                .process(Populate.from(
                        Populate.exchange(Populate.headerValue("cloudId", Long.class))
                                .to(Populate.message(Populate.header("CamelJpaParameters")), Populate.map("cloudId"))
                ))
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c where c.id = :cloudId"))
                .choice()
                .when(simple("${body.size} == 0"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Not found\n"))
                .stop()
                .end()
                .setBody(simple("${body[0]}"))
                .setProperty("cloudcatcher.minioObjectName", simple("${body.minioObjectName}"))
                .setHeader(MinioConstants.OBJECT_NAME, exchangeProperty("cloudcatcher.minioObjectName"))
                .to(minio("bucket").operation("getObject"))
                .convertBodyTo(byte[].class)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .choice()
                .when(simple("${exchangeProperty.cloudcatcher.minioObjectName} regex '(?i).*\\\\.png$'"))
                .setHeader(Exchange.CONTENT_TYPE, constant("image/png"))
                .otherwise()
                .setHeader(Exchange.CONTENT_TYPE, constant("image/jpeg"))
                .end();

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.minio.events")
                .routingKey("cloud.minio.event")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(new ParseMinioEvent())
                .log("minio event received: name=${exchangeProperty.cloudcatcher.minioEventName} key=${exchangeProperty.cloudcatcher.minioObjectName}")
                .filter(exchangeProperty("cloudcatcher.minioObjectName").isNotNull())
                .choice()
                .when(simple("${exchangeProperty.cloudcatcher.minioEventName} startsWith 's3:ObjectCreated:'"))
                .to(direct("handle-minio-object-created"))
                .when(simple("${exchangeProperty.cloudcatcher.minioEventName} startsWith 's3:ObjectRemoved:'"))
                .to(direct("handle-minio-object-removed"))
                .end()
                .end();

        from(direct("handle-minio-object-created"))
                .process(new PopulateUserLookupJpaParams())
                .to(jpa(User.class.getCanonicalName()).query("select u from User u where u.userName = :userName"))
                .process(new TestForNewUserProcessor())
                .choice()
                .when(exchangeProperty("cloudcatcher.newUser").isEqualTo(true))
                .to(jpa(User.class.getCanonicalName()).usePersist(true))
                .end()
                .process(new PopulateCloudLookupJpaParams())
                .to(jpa(Cloud.class.getCanonicalName()).query("select c from Cloud c where c.minioObjectName = :minioObjectName"))
                .process(new UpsertCloudEntityFromUploadEvent())
                .choice()
                .when(exchangeProperty("cloudcatcher.newCloudEntity").isEqualTo(true))
                .to(jpa(Cloud.class.getCanonicalName()).usePersist(true))
                .otherwise()
                .to(jpa(Cloud.class.getCanonicalName()))
                .end()
                .process(new EmitCloudUpsertStreamEvent())
                .choice()
                .when(exchangeProperty("cloudcatcher.newCloudEntity").isEqualTo(true))
                .process(new BuildCloudClassificationRequestMessage())
                .setExchangePattern(org.apache.camel.ExchangePattern.InOnly)
                .to(springRabbitmq("cloud-classifier-exchange").routingKey("cloud.general.classifier.request"))
                .end();
    }

    private class ParseMinioEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            String body = exchange.getMessage().getBody(String.class);
            if (body == null || body.isBlank()) {
                return;
            }

            JsonNode document = objectMapper.readTree(body);
            JsonNode firstRecord = document.path("Records").isArray() && document.path("Records").size() > 0
                    ? document.path("Records").get(0)
                    : null;
            if (firstRecord == null) {
                return;
            }

            String eventName = exchange.getMessage().getHeader("minio-event", String.class);
            if (eventName == null || eventName.isBlank()) {
                eventName = firstRecord.path("eventName").asText("");
            }
            if (eventName == null || eventName.isBlank()) {
                eventName = document.path("EventName").asText("");
            }
            if (eventName == null || eventName.isBlank()) {
                return;
            }
            exchange.setProperty("cloudcatcher.minioEventName", eventName);

            String encodedKey = firstRecord.path("s3").path("object").path("key").asText("");
            if (encodedKey.isBlank()) {
                encodedKey = document.path("Key").asText("");
                if (encodedKey.startsWith("bucket/")) {
                    encodedKey = encodedKey.substring("bucket/".length());
                }
            }
            if (encodedKey.isBlank()) {
                return;
            }

            String objectKey = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
            String[] parts = objectKey.split("/");
            if (parts.length < 4 || !"uploads".equals(parts[0]) || !"clouds".equals(parts[1])) {
                return;
            }

            String userName = parts[2];
            String originalFileName = parts[parts.length - 1];
            String eTag = firstRecord.path("s3").path("object").path("eTag").asText("");

            exchange.setProperty("cloudcatcher.userName", userName);
            exchange.setProperty("cloudcatcher.minioObjectName", objectKey);
            exchange.setProperty("cloudcatcher.minioETag", eTag);
            exchange.setProperty("cloudcatcher.originalFileName", originalFileName);
        }
    }

    private static class UpsertCloudEntityFromUploadEvent implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            User user = exchange.getProperty("cloudcatcher.userEntity", User.class);
            String minioObjectName = exchange.getProperty("cloudcatcher.minioObjectName", String.class);
            String minioETag = exchange.getProperty("cloudcatcher.minioETag", String.class);
            String originalFileName = exchange.getProperty("cloudcatcher.originalFileName", String.class);

            Cloud cloud;
            boolean isNewCloud = clouds == null || clouds.isEmpty();
            if (isNewCloud) {
                cloud = new Cloud(minioObjectName, minioETag, user, originalFileName);
            } else {
                cloud = clouds.get(0);
                cloud.setMinioObjectName(minioObjectName);
                cloud.setMinioETag(minioETag);
                cloud.setOriginalFileName(originalFileName);
                cloud.setDeletionRequested(false);
            }

            exchange.setProperty("cloudcatcher.newCloudEntity", isNewCloud);
            exchange.getMessage().setBody(cloud);
        }
    }

    private class BuildCloudClassificationRequestMessage implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            if (cloud == null || cloud.getId() == null || cloud.getUser() == null) {
                exchange.setRouteStop(true);
                return;
            }
            String imageUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("bucket")
                            .object(cloud.getMinioObjectName())
                            .expiry(60 * 60)
                            .build()
            );
            Map<String, Object> classificationRequest = new HashMap<>();
            classificationRequest.put("documentType", "GeneralClassificationRequest");
            classificationRequest.put("cloudId", cloud.getId());
            classificationRequest.put("userName", cloud.getUser().getUserName());
            classificationRequest.put("minioObjectName", cloud.getMinioObjectName());
            classificationRequest.put("imageUrl", imageUrl);
            classificationRequest.put("occurredAt", Instant.now().toString());
            exchange.getMessage().setBody(objectMapper.writeValueAsString(classificationRequest));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setHeader("CamelSpringRabbitmqDeliveryMode", "PERSISTENT");
        }
    }

    private static class PopulateCloudsItems implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            List<Map<String, Object>> items = clouds.stream().map(c -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", c.getId());
                item.put("cloudName", c.getCloudName());
                item.put("preventFurtherProcessing", c.isPreventFurtherProcessing());
                if (c.getGeneralClassificationDecision() != null) {
                    item.put("deniedByLabel", c.getGeneralClassificationDecision().getLabel());
                    item.put("deniedByScore", c.getGeneralClassificationDecision().getScore());
                }
                String[] target = extractDownloadTargetFromMinioObjectName(c.getMinioObjectName());
                if (target != null) {
                    item.put("downloadUserId", target[0]);
                    item.put("downloadFileName", target[1]);
                }
                return item;
            }).toList();
            exchange.getMessage().setBody(Map.of("clouds", items));
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

    private class CreateClassificationOutboxEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            String imageUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("bucket")
                            .object(cloud.getMinioObjectName())
                            .expiry(60 * 60)
                            .build()
            );
            Map<String, Object> classificationRequest = new HashMap<>();
            classificationRequest.put("documentType", "CloudClassificationRequest");
            classificationRequest.put("cloudId", cloud.getId());
            classificationRequest.put("userName", cloud.getUser().getUserName());
            classificationRequest.put("minioObjectName", cloud.getMinioObjectName());
            classificationRequest.put("imageUrl", imageUrl);
            classificationRequest.put("occurredAt", Instant.now().toString());
            String payload = objectMapper.writeValueAsString(classificationRequest);
            exchange.getMessage().setBody(new OutboxEvent("CloudClassificationRequested", cloud.getId(), payload));
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
