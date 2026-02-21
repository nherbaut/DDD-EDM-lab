package fr.u.bordeaux.iut.ddd.resources;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.ProducerTemplate;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Path("/clouds")
public class TestStreamResource {

    @Inject
    TestSseBridge bridge;

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    SecurityIdentity securityIdentity;


    @RolesAllowed("viewer")
    @POST
    @Path("/upload-request")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> sse(Map<String, Object> payload) {
        String correlationId = UUID.randomUUID().toString();
        Multi<String> stream = bridge.register(correlationId);
        String userName = securityIdentity.getPrincipal() == null ? "anonymous" : securityIdentity.getPrincipal().getName();
        String fileExtension = resolveFileExtension(payload);
        producerTemplate.sendBodyAndHeaders("seda:upload-request", "",
                Map.of("correlationId", correlationId, "userName", userName, "fileExtension", fileExtension));
        return stream;
    }

    @RolesAllowed("viewer")
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> stream() {
        String userName = securityIdentity.getPrincipal() == null ? "anonymous" : securityIdentity.getPrincipal().getName();
        return bridge.register(userName);
    }

    private static String resolveFileExtension(Map<String, Object> payload) {
        if (payload != null) {
            Object fileNameValue = payload.get("fileName");
            if (fileNameValue != null) {
                String fileName = String.valueOf(fileNameValue).trim();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot >= 0 && lastDot + 1 < fileName.length()) {
                    return fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                }
            }

            Object contentTypeValue = payload.get("contentType");
            if (contentTypeValue != null) {
                String contentType = String.valueOf(contentTypeValue).toLowerCase(Locale.ROOT);
                if (contentType.equals("image/jpeg") || contentType.equals("image/jpg")) {
                    return "jpg";
                }
                if (contentType.equals("image/png")) {
                    return "png";
                }

                if (contentType.startsWith("image/")) {
                    return contentType.substring("image/".length());
                }
            }
        }
        return "bin";
    }
}
