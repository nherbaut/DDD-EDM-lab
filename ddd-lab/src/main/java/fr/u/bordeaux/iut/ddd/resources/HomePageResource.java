package fr.u.bordeaux.iut.ddd.resources;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/")
public class HomePageResource {

    @Inject
    Template index;

    @Inject
    SecurityIdentity identity;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        if (identity != null && !identity.isAnonymous()) {
            throw new WebApplicationException(Response.seeOther(URI.create("/users/me")).build());
        }
        return index.data("title", "CloudCatcher");
    }
}
