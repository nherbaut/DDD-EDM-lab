package fr.u.bordeaux.iut.ddd.camel.routes;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

@ApplicationScoped
public class CamelRoutes extends EndpointRouteBuilder {

    @Inject
    @Named("springRabbitConnectionFactory")
    ConnectionFactory springRabbitConnectionFactory;

    @Override
    public void configure() {
        from(timer("rabbitmq-topology-init").delay(1000).repeatCount(1))
                .process(new DeclareRabbitTopology())
                .log("RabbitMQ topology initialized (exchange=cloud-classifier-exchange)");
    }

    private class DeclareRabbitTopology implements Processor {
        @Override
        public void process(Exchange exchange) {
            RabbitAdmin admin = new RabbitAdmin(springRabbitConnectionFactory);

            DirectExchange exchangeDef = new DirectExchange("cloud-classifier-exchange", true, false);
            Queue requestQueue = new Queue("cloud.classifier.requests", false);
            Queue resultQueue = new Queue("cloud.classifier.results", false);
            Queue eventQueue = new Queue("cloud.minio.events", false);
            Queue deleteRequestQueue = new Queue("cloud.delete.requests", false);
            Binding requestBinding = BindingBuilder.bind(requestQueue).to(exchangeDef).with("cloud.classify.requests");
            Binding resultBinding = BindingBuilder.bind(resultQueue).to(exchangeDef).with("cloud.classify.result");
            Binding eventBinding = BindingBuilder.bind(eventQueue).to(exchangeDef).with("cloud.minio.event");
            Binding deleteRequestBinding = BindingBuilder.bind(deleteRequestQueue).to(exchangeDef).with("cloud.delete.request");

            admin.declareExchange(exchangeDef);
            admin.declareQueue(requestQueue);
            admin.declareQueue(resultQueue);
            admin.declareQueue(eventQueue);
            admin.declareQueue(deleteRequestQueue);
            admin.declareBinding(requestBinding);
            admin.declareBinding(resultBinding);
            admin.declareBinding(eventBinding);
            admin.declareBinding(deleteRequestBinding);
        }
    }
}
