package fr.u.bordeaux.iut.ddd.camel.processor;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class PopulateUserName implements Processor {

    public static final String DEFAULT_HEADER_NAME = "userName";
    public static final String AUTH_USER_HEADER = "CamelVertxPlatformHttpAuthenticatedUser";

    private final String headerName;

    public static PopulateUserName fromSecurityContext() {
        return new PopulateUserName(DEFAULT_HEADER_NAME);
    }

    public static PopulateUserName fromSecurityContext(String headerName) {
        return new PopulateUserName(headerName);
    }

    private PopulateUserName(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public void process(Exchange exchange) {
        String headerValue="anonymous";
        Message message = exchange.getMessage();
        QuarkusHttpUser user = message.getHeader(AUTH_USER_HEADER, QuarkusHttpUser.class);
        SecurityIdentity securityIdentity = user == null ? null : user.getSecurityIdentity();
        if (securityIdentity != null && securityIdentity.getPrincipal() !=null
        && !securityIdentity.getPrincipal().getName().isBlank()) {
            headerValue=securityIdentity.getPrincipal().getName();

        }
        message.setHeader(headerName, headerValue);
    }
}
