package fr.u.bordeaux.iut.ddd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.u.bordeaux.iut.ddd.model.Cloud;
import fr.u.bordeaux.iut.ddd.model.OutboxEvent;
import fr.u.bordeaux.iut.ddd.model.User;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.minio.MinioConstants;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class CamelRoutes extends EndpointRouteBuilder {

    @Inject
    MinioClient minioClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "cloudcatcher.public-base-url", defaultValue = "http://localhost:8080")
    String publicBaseUrl;

    @ConfigProperty(name = "cloudcatcher.classifier-fetch-token", defaultValue = "dev-classifier-token")
    String classifierFetchToken;

    @ConfigProperty(name = "GENERAL_CLASSIFICATION_URL", defaultValue = "http://localhost:8000/predict")
    String generalClassificationUrl;

    @ConfigProperty(name = "GENERAL_CLASSIFICATION_THRESHOLD", defaultValue = "0.5")
    double generalClassificationThreshold;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void configure() throws Exception {
        onException(Exception.class)
                .onWhen(exchangeProperty("cloudcatcher.outboxEventId").isNotNull())
                .handled(true)
                .process(new MarkOutboxEventFailure())
                .to(jpa(OutboxEvent.class.getCanonicalName()))
                .log("Classification dispatch retry failed for outboxId=${exchangeProperty.cloudcatcher.outboxEventId}: ${exception.message}");

        //new cloud path
        from(platformHttp("/clouds").httpMethodRestrict("POST"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .process(GuardContentType.gardMime(GuardContentType.MimeType.IMAGE))
                .process(new PopulateMinioHeaders())
                .log("Received /clouds upload")
                .to(minio("bucket").autoCreateBucket(true)) //send the file to storage
                .to(direct("cloud-insert-db"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Upload accepted\n"));

        from(direct("cloud-insert-db"))
                .process(new PopulateJPAParamaterForUser())
                .to(jpa(User.class.getCanonicalName()).query("select u from User u where u.userName = :userName"))
                .process(new TestForNewUser())
                .choice()
                    .when(exchangeProperty("cloudcatcher.newUser").isEqualTo(true))
                    .to(jpa(User.class.getCanonicalName()).usePersist(true))
                .end()
                .process(new CreateNewCloudEntity())
                .to(jpa(Cloud.class.getCanonicalName()).usePersist(true))
                .process(new CreateClassificationOutboxEvent())
                .to(jpa(OutboxEvent.class.getCanonicalName()).usePersist(true));

        from(timer("cloud-classification-dispatch").period(60000).delay(60000))
                .to(jpa("fr.u.bordeaux.iut.ddd.model.OutboxEvent").query("select e from OutboxEvent e where e.status = 'PENDING' order by e.createdAt"))
                .split(body())
                .to(direct("dispatch-classification-outbox-event"));

        from(direct("dispatch-classification-outbox-event"))
                .process(new PrepareOutboxContext())
                .process(new SetCloudLookupJpaParameters())
                .to(jpa("fr.u.bordeaux.iut.ddd.model.Cloud?query=select c from Cloud c where c.id = :cloudId"))
                .process(new ApplyGeneralClassificationContentFilter())
                .choice()
                .when(exchangeProperty("cloudcatcher.persistFilteredCloud").isEqualTo(true))
                    .process(exchange -> exchange.getMessage().setBody(exchange.getProperty("cloudcatcher.cloudEntity", Cloud.class)))
                    .to(jpa(Cloud.class.getCanonicalName()))
                .end()
                .choice()
                .when(exchangeProperty("cloudcatcher.forwardToCloudClassification").isEqualTo(true))
                    .process(new PrepareOutboxDispatchMessage())
                    .setExchangePattern(ExchangePattern.InOnly)
                    .to(springRabbitmq("cloud-classifier-exchange").queues("cloud.classifier.requests").disableReplyTo(true).autoDeclareProducer(true).routingKey("cloud.classify.requests"))
                .end()
                .process(new MarkOutboxEventSent())
                .to(jpa(OutboxEvent.class.getCanonicalName()));

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
                .to(jpa("fr.u.bordeaux.iut.ddd.model.Cloud").query("select c from Cloud c where c.user.userName = :userName"))
                .process(new PopulateCloudsItems())
                .marshal().json(JsonLibrary.Jsonb)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from(platformHttp("/cloud/{userid}/{fileName}")
                .httpMethodRestrict("GET"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .setHeader(MinioConstants.OBJECT_NAME, simple("cloud/${header.userid}/${header.fileName}"))
                .doTry()
                    .to(minio("bucket").operation("getObject"))
                    .convertBodyTo(byte[].class)
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                    .choice()
                        .when(simple("${header.fileName} regex '(?i).*\\\\.png$'"))
                            .setHeader(Exchange.CONTENT_TYPE, constant("image/png"))
                        .otherwise()
                            .setHeader(Exchange.CONTENT_TYPE, constant("image/jpeg"))
                    .end()
                .endDoTry()
                .doCatch(ErrorResponseException.class)
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                    .setBody(constant("Not found\n"))
                .end();

        from(platformHttp("/clouds/delete").httpMethodRestrict("POST"))
                .process(RoleCheckProcessor.checkRole("viewer"))
                .unmarshal().json(JsonLibrary.Jsonb, Map.class)
                .process(new ExtractDeleteCloudIds())
                .process(new GuardEmptyCloudsIds())
                .process(new BuildDeleteCloudJpaParams())
                .to(jpa("fr.u.bordeaux.iut.ddd.model.Cloud").query("select c from Cloud c where c.id in :ids and c.user.userName = :userName"))
                .process(new GuardCloudOwnership())
                .split(body())
                    .process(new PopulateDeleteCloudHeaders())
                    .multicast()
                    .stopOnException()
                    .to(direct("delete-cloud-jpa"), direct("delete-cloud-minio"))
                    .end()
                .end()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant("Deleted\n"));

        from(direct("delete-cloud-jpa")).to(jpa("fr.u.bordeaux.iut.ddd.model.Cloud").useExecuteUpdate(true).query("delete from Cloud c where c.id = :cloudId"));

        from(direct("delete-cloud-minio")).to(minio("bucket").operation("deleteObject"));

        from(platformHttp("/clouddb/{cloudId}").httpMethodRestrict("GET")).process(exchange -> {
            String token = exchange.getMessage().getHeader("token", String.class);
            if (!classifierFetchToken.equals(token)) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 403);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain");
                exchange.getMessage().setBody("Forbidden\n");
                exchange.setRouteStop(true);
                return;
            }
            Long cloudId = exchange.getMessage().getHeader("cloudId", Long.class);
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloudId);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
        }).to("jpa:fr.u.bordeaux.iut.ddd.model.Cloud?query=select c from Cloud c where c.id = :cloudId").process(exchange -> {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain");
                exchange.getMessage().setBody("Not found\n");
                exchange.setRouteStop(true);
                return;
            }
            Cloud cloud = clouds.get(0);
            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder().bucket("bucket").object(cloud.getMinioObjectName()).build()); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                inputStream.transferTo(outputStream);
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, contentTypeForObject(cloud.getMinioObjectName()));
                exchange.getMessage().setBody(outputStream.toByteArray());
            }
        });

        from(springRabbitmq("cloud-classifier-exchange").queues("cloud.classifier.results").routingKey("cloud.classify.result").exchangeType("direct").autoDeclare(true)).process(exchange -> {
            String body = exchange.getMessage().getBody(String.class);
            JsonNode doc = objectMapper.readTree(body);
            long cloudId = doc.get("cloudId").asLong();
            String cloudName = doc.get("cloudName").asText();
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloudId);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
            exchange.setProperty("cloudcatcher.documentCloudName", cloudName);
        }).to(jpa("fr.u.bordeaux.iut.ddd.model.Cloud").query("select c from Cloud c where c.id = :cloudId")).process(exchange -> {
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
        }).to(jpa("fr.u.bordeaux.iut.ddd.model.Cloud"));
    }


    private static String contentTypeForObject(String objectName) {
        String lower = objectName == null ? "" : objectName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    private static class PopulateJPAParamaterForUser implements Processor {
        @Override
        public void process(Exchange exchange) {
            String userName = exchange.getProperty("cloudcatcher.userName", String.class);
            String minioObjectName = exchange.getMessage().getHeader("CamelMinioObjectName", String.class);
            String minioETag = exchange.getMessage().getHeader("CamelMinioETag", String.class);

            exchange.setProperty("cloudcatcher.minioObjectName", minioObjectName);
            exchange.setProperty("cloudcatcher.minioETag", minioETag);
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("userName", userName);
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
        }
    }

    private static class TestForNewUser implements Processor {
        @Override
        public void process(Exchange exchange) {
            List<User> users = exchange.getMessage().getBody(List.class);
            boolean isNewUser = users == null || users.isEmpty();
            User user = isNewUser ? new User(exchange.getProperty("cloudcatcher.userName", String.class)) : users.get(0);
            exchange.setProperty("cloudcatcher.userEntity", user);
            exchange.setProperty("cloudcatcher.newUser", isNewUser);
            exchange.getMessage().setBody(user);
        }
    }

    private static class CreateNewCloudEntity implements Processor {
        @Override
        public void process(Exchange exchange) {
            User user = exchange.getProperty("cloudcatcher.userEntity", User.class);
            String minioObjectName = exchange.getProperty("cloudcatcher.minioObjectName", String.class);
            String minioETag = exchange.getProperty("cloudcatcher.minioETag", String.class);
            String originalFileName = exchange.getProperty("cloudcatcher.originalFileName", String.class);
            Cloud cloud = new Cloud(minioObjectName, minioETag, user, originalFileName);
            exchange.getMessage().setBody(cloud);
        }
    }

    private static class PopulateCloudsItems implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            List<Map<String, Object>> items = clouds.stream().map(c -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", c.getId());
                item.put("minioObjectName", c.getMinioObjectName());
                item.put("cloudName", c.getCloudName());
                item.put("preventFurtherProcessing", c.isPreventFurtherProcessing());
                return item;
            }).toList();
            exchange.getMessage().setBody(Map.of("clouds", items));
        }
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

    private static class PopulateDeleteCloudHeaders implements Processor {
        @Override
        public void process(Exchange exchange) {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            exchange.getMessage().setHeader(MinioConstants.OBJECT_NAME, cloud.getMinioObjectName());
            Map<String, Object> jpaParams = new HashMap<>();
            jpaParams.put("cloudId", cloud.getId());
            exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
        }
    }

    private class CreateClassificationOutboxEvent implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Cloud cloud = exchange.getMessage().getBody(Cloud.class);
            String token = URLEncoder.encode(classifierFetchToken, StandardCharsets.UTF_8);
            String imageUrl = publicBaseUrl + "/clouddb/" + cloud.getId() + "?token=" + token;
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


    private static class PrepareOutboxDispatchMessage implements Processor {
        @Override
        public void process(Exchange exchange) {
            OutboxEvent event = exchange.getProperty("cloudcatcher.outboxEvent", OutboxEvent.class);
            if (event == null) {
                event = exchange.getMessage().getBody(OutboxEvent.class);
                exchange.setProperty("cloudcatcher.outboxEvent", event);
                exchange.setProperty("cloudcatcher.outboxEventId", event == null ? null : event.getId());
            }
            exchange.getMessage().setBody(event == null ? null : event.getPayload());
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setHeader("CamelSpringRabbitmqDeliveryMode", "PERSISTENT");
            exchange.getMessage().setHeader("messageId", event == null ? null : String.valueOf(event.getId()));
        }
    }

    private static class PrepareOutboxContext implements Processor {
        @Override
        public void process(Exchange exchange) {
            OutboxEvent event = exchange.getMessage().getBody(OutboxEvent.class);
            exchange.setProperty("cloudcatcher.outboxEvent", event);
            exchange.setProperty("cloudcatcher.outboxEventId", event == null ? null : event.getId());
            exchange.setProperty("cloudcatcher.forwardToCloudClassification", true);
            exchange.setProperty("cloudcatcher.persistFilteredCloud", false);
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

    private class ApplyGeneralClassificationContentFilter implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            List<Cloud> clouds = exchange.getMessage().getBody(List.class);
            if (clouds == null || clouds.isEmpty()) {
                exchange.setProperty("cloudcatcher.forwardToCloudClassification", false);
                return;
            }

            Cloud cloud = clouds.get(0);
            exchange.setProperty("cloudcatcher.cloudEntity", cloud);
            if (cloud.isPreventFurtherProcessing()) {
                exchange.setProperty("cloudcatcher.forwardToCloudClassification", false);
                return;
            }

            byte[] imageBytes;
            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder().bucket("bucket").object(cloud.getMinioObjectName()).build()); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                inputStream.transferTo(outputStream);
                imageBytes = outputStream.toByteArray();
            }

            JsonNode classificationDoc = callGeneralClassification(imageBytes, contentTypeForObject(cloud.getMinioObjectName()));
            if (matchesGeneralClassificationThreshold(classificationDoc, generalClassificationThreshold)) {
                cloud.setPreventFurtherProcessing(true);
                exchange.setProperty("cloudcatcher.persistFilteredCloud", true);
                exchange.setProperty("cloudcatcher.forwardToCloudClassification", false);
            } else {
                exchange.setProperty("cloudcatcher.forwardToCloudClassification", true);
            }
        }
    }

    private JsonNode callGeneralClassification(byte[] imageBytes, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(generalClassificationUrl)).header(Exchange.CONTENT_TYPE, contentType).POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("General classification HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
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
}
