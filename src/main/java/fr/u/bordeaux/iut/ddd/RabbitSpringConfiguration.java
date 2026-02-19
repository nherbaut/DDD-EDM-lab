package fr.u.bordeaux.iut.ddd;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

@ApplicationScoped
public class RabbitSpringConfiguration {

    @ConfigProperty(name = "rabbitmq-host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "rabbitmq-port", defaultValue = "5672")
    int port;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String username;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String password;

    @Produces
    @Named("springRabbitConnectionFactory")
    ConnectionFactory springRabbitConnectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        return factory;
    }
}
