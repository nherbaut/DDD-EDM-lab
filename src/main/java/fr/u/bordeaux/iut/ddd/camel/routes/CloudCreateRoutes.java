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
import org.apache.camel.model.dataformat.JsonLibrary;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.minio.http.Method;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudCreateRoutes extends EndpointRouteBuilder {
    private static final Logger LOG = Logger.getLogger(CloudCreateRoutes.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TestSseBridge testSseBridge;

    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "cloudcatcher.classifier-fetch-token", defaultValue = "dev-classifier-token")
    String classifierFetchToken;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-endpoint", defaultValue = "http://minio:9000")
    String classifierMinioEndpoint;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-access-key", defaultValue = "adminadmin")
    String classifierMinioAccessKey;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-secret-key", defaultValue = "adminadmin")
    String classifierMinioSecretKey;

    @ConfigProperty(name = "cloudcatcher.classifier-minio-region", defaultValue = "us-east-1")
    String classifierMinioRegion;

    private volatile MinioClient classifierMinioClient;

    @ConfigProperty(name = "cloudcatcher.public-minio-endpoint", defaultValue = "http://localhost:9000")
    String publicMinioEndpoint;

    @ConfigProperty(name = "cloudcatcher.public-minio-access-key", defaultValue = "adminadmin")
    String publicMinioAccessKey;

    @ConfigProperty(name = "cloudcatcher.public-minio-secret-key", defaultValue = "adminadmin")
    String publicMinioSecretKey;

    @ConfigProperty(name = "cloudcatcher.public-minio-region", defaultValue = "us-east-1")
    String publicMinioRegion;

    private volatile MinioClient publicMinioClient;

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
                .process(exchange -> {
                    String userName = exchange.getMessage().getHeader("userName", String.class);
                    String fileExtension = exchange.getMessage().getHeader("fileExtension", String.class);
                    String objectName = "uploads/clouds/" + userName + "/" + UUID.randomUUID() + "." + fileExtension;
                    exchange.getMessage().setHeader(MinioConstants.OBJECT_NAME, objectName);
                })
                .process(new CreatePublicUploadLink())
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
                .process(new CreatePublicDownloadLink())
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
                .process(new GuardCloudFoundById())
                .process(new SelectFirstCloudFromJpaResult())
                .process(new CaptureMinioObjectName())
                .setHeader(MinioConstants.OBJECT_NAME, exchangeProperty("cloudcatcher.minioObjectName"))
                .to(minio("bucket").operation("getObject"))
                .convertBodyTo(byte[].class)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .process(new SetContentTypeFromObjectName());

        from(springRabbitmq("cloud-classifier-exchange")
                .queues("cloud.minio.events")
                .routingKey("cloud.minio.event")
                .exchangeType("direct")
                .autoDeclare(true))
                .process(new ParseMinioEvent())
                .process(exchange -> LOG.infof(
                        "minio event received: name=%s key=%s",
                        exchange.getProperty("cloudcatcher.minioEventName", String.class),
                        exchange.getProperty("cloudcatcher.minioObjectName", String.class)))
                .filter(exchangeProperty("cloudcatcher.minioObjectName").isNotNull())
                .choice()
                .when(exchangeProperty("cloudcatcher.minioEventName").startsWith("s3:ObjectCreated:"))
                .to(direct("handle-minio-object-created"))
                .when(exchangeProperty("cloudcatcher.minioEventName").startsWith("s3:ObjectRemoved:"))
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

    private class CreatePublicUploadLink implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            String objectName = exchange.getMessage().getHeader(MinioConstants.OBJECT_NAME, String.class);
            String uploadUrl = publicMinioClient().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket("bucket")
                            .object(objectName)
                            .region(publicMinioRegion)
                            .expiry(60 * 60)
                            .build()
            );
            exchange.getMessage().setBody(uploadUrl);
        }
    }

    private class CreatePublicDownloadLink implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            String objectName = exchange.getMessage().getHeader(MinioConstants.OBJECT_NAME, String.class);
            String downloadUrl = publicMinioClient().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("bucket")
                            .object(objectName)
                            .region(publicMinioRegion)
                            .expiry(60 * 60)
                            .build()
            );
            exchange.getMessage().setBody(downloadUrl);
        }
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

    private static class GuardCloudFoundById implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain");
                exchange.getMessage().setBody("Not found\n");
                exchange.setRouteStop(true);
            }
        }
    }

    private static class SelectFirstCloudFromJpaResult implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                return;
            }
            exchange.getMessage().setBody(clouds.get(0));
        }
    }

    private static class CaptureMinioObjectName implements Processor {
        @Override
        public void process(Exchange exchange) {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            if (cloud == null) {
                return;
            }
            exchange.setProperty("cloudcatcher.minioObjectName", cloud.getMinioObjectName());
        }
    }

    private static class SetContentTypeFromObjectName implements Processor {
        @Override
        public void process(Exchange exchange) {
            String objectName = exchange.getProperty("cloudcatcher.minioObjectName", String.class);
            String contentType = "image/jpeg";
            if (objectName != null && objectName.toLowerCase().endsWith(".png")) {
                contentType = "image/png";
            }
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, contentType);
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
            String imageUrl = buildClassifierPresignedUrl(cloud.getMinioObjectName());
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
            String imageUrl = buildClassifierPresignedUrl(cloud.getMinioObjectName());
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

    private MinioClient publicMinioClient() {
        MinioClient existing = publicMinioClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (publicMinioClient == null) {
                publicMinioClient = MinioClient.builder()
                        .endpoint(publicMinioEndpoint)
                        .credentials(publicMinioAccessKey, publicMinioSecretKey)
                        .build();
            }
            return publicMinioClient;
        }
    }
}
