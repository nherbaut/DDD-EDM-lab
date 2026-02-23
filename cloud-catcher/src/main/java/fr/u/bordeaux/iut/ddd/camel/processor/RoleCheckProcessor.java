package fr.u.bordeaux.iut.ddd.camel.processor;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class RoleCheckProcessor implements Processor {

    private final String role;

    private RoleCheckProcessor(String role) {
        this.role = role;
    }

    public static RoleCheckProcessor checkRole(String role) {
        return new RoleCheckProcessor(role);

    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message message = exchange.getMessage();
        QuarkusHttpUser user = message.getHeader("CamelVertxPlatformHttpAuthenticatedUser", QuarkusHttpUser.class);
        SecurityIdentity securityIdentity = user == null ? null : user.getSecurityIdentity();
        boolean authenticated = securityIdentity != null && !securityIdentity.isAnonymous();
        boolean hasRole = authenticated && securityIdentity.hasRole(this.role);
        boolean allowInDev = LaunchMode.current() == LaunchMode.DEVELOPMENT && authenticated;
        if (!(hasRole || allowInDev)) {
            message.setHeader(Exchange.HTTP_RESPONSE_CODE, 403);
            message.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            message.setBody("Forbidden\n");
            exchange.setRouteStop(true);
            return;
        }
        exchange.setProperty("cloudcatcher.userName", securityIdentity.getPrincipal().getName());

    }
}
