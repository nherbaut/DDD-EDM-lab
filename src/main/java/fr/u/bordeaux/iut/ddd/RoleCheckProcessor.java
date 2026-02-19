package fr.u.bordeaux.iut.ddd;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

class RoleCheckProcessor implements Processor {

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
        if (securityIdentity == null || !securityIdentity.hasRole(this.role)) {
            message.setHeader(Exchange.HTTP_RESPONSE_CODE, 403);
            message.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            message.setBody("Forbidden\n");
            exchange.setRouteStop(true);
            return;
        }
        exchange.setProperty("cloudcatcher.userName", securityIdentity.getPrincipal().getName());

    }
}
