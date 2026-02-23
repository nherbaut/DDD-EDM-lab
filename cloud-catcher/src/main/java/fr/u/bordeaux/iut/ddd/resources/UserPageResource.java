package fr.u.bordeaux.iut.ddd.resources;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import org.apache.camel.ProducerTemplate;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/users/me")
@Authenticated
public class UserPageResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    ProducerTemplate producerTemplate;


    @CheckedTemplate
    public class Templates {
        public static native TemplateInstance usersMe();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance mePage() {
        if(null!=identity && !identity.isAnonymous()) {
            producerTemplate.sendBodyAndHeader(
                    "direct:emit-user-created-event",
                    "",
                    "userName",
                    identity.getPrincipal().getName()
            );
            if (identity.hasRole("accountant")) {
                throw new WebApplicationException(Response.seeOther(URI.create("/accountant/me")).build());
            }
            return Templates.usersMe();
        } else{
            throw new WebApplicationException(Response.seeOther(URI.create("/")).build());
        }


    }
}
