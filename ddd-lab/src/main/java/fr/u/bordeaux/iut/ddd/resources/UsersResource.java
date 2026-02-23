package fr.u.bordeaux.iut.ddd.resources;

import fr.u.bordeaux.iut.ddd.model.User;
import io.minio.MinioClient;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;

import java.net.URI;

@Path("/api/users")

public class UsersResource {

    @Inject
    SecurityIdentity identity;


    @Inject //(1)
    MinioClient minioClient;

    @Authenticated
    @GET
    @Path("/me")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public User me() {

        return new User(identity);
    }




}
