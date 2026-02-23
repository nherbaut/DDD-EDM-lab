package fr.u.bordeaux.iut.ddd.resources;

import fr.u.bordeaux.iut.ddd.model.User;
import fr.u.bordeaux.iut.ddd.conf.QuotaProjectionService;
import io.minio.MinioClient;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.NoCache;

import java.util.HashMap;
import java.util.Map;

@Path("/api/users")

public class UsersResource {

    @Inject
    SecurityIdentity identity;


    @Inject //(1)
    MinioClient minioClient;

    @Inject
    QuotaProjectionService quotaProjectionService;

    @Authenticated
    @GET
    @Path("/me")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> me() {
        String userName = identity.getPrincipal().getName();
        User user = new User(identity);
        Integer quota = quotaProjectionService.getQuota(userName);

        Map<String, Object> payload = new HashMap<>();
        payload.put("userName", userName);
        payload.put("preferred_username", userName);
        payload.put("username", userName);
        payload.put("quota", quota);
        payload.put("remainingQuota", quota);
        payload.put("id", user.getId());
        return payload;
    }




}
