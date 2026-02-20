package fr.u.bordeaux.iut.ddd.camel.processor;

import fr.u.bordeaux.iut.ddd.model.User;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.List;

public class TestForNewUserProcessor implements Processor {
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
