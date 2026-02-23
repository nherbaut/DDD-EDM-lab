package fr.u.bordeaux.iut.ddd.resources;

import fr.u.bordeaux.iut.ddd.conf.AccountantProjectionService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.NoCache;

import java.util.Map;

@Path("/api/accounting")
@Authenticated
public class AccountantResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    AccountantProjectionService accountantProjectionService;

    @GET
    @Path("/summary")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> summary() {
        if (identity == null || !identity.hasRole("accountant")) {
            throw new ForbiddenException("Accountant role required");
        }
        return accountantProjectionService.summary();
    }
}
