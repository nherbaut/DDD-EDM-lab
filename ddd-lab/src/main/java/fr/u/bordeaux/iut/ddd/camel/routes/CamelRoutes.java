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
            Queue requestQueue = new Queue("cloud.cloud-classifier.request", false);
            Queue resultQueue = new Queue("cloud.cloud-classifier.results", false);
            Queue generalRequestQueue = new Queue("cloud.general.classifier.request", false);
            Queue generalResultQueue = new Queue("cloud.general.classifier.results", false);
            Queue generalInvalidQueue = new Queue("cloud.general.classifier.invalid", false);
            Queue cloudInvalidQueue = new Queue("cloud.cloud-classifier.invalid", false);
            Queue eventQueue = new Queue("cloud.minio.events", false);
            Queue deleteRequestQueue = new Queue("cloud.delete.requests", false);
            Binding requestBinding = BindingBuilder.bind(requestQueue).to(exchangeDef).with("cloud.cloud-classifier.request");
            Binding resultBinding = BindingBuilder.bind(resultQueue).to(exchangeDef).with("cloud.cloud-classifier.results");
            Binding generalRequestBinding = BindingBuilder.bind(generalRequestQueue).to(exchangeDef).with("cloud.general.classifier.request");
            Binding generalResultBinding = BindingBuilder.bind(generalResultQueue).to(exchangeDef).with("cloud.general.classifier.results");
            Binding generalInvalidBinding = BindingBuilder.bind(generalInvalidQueue).to(exchangeDef).with("cloud.general.classifier.invalid");
            Binding cloudInvalidBinding = BindingBuilder.bind(cloudInvalidQueue).to(exchangeDef).with("cloud.cloud-classifier.invalid");
            Binding eventBinding = BindingBuilder.bind(eventQueue).to(exchangeDef).with("cloud.minio.event");
            Binding deleteRequestBinding = BindingBuilder.bind(deleteRequestQueue).to(exchangeDef).with("cloud.delete.request");

            admin.declareExchange(exchangeDef);
            admin.declareQueue(requestQueue);
            admin.declareQueue(resultQueue);
            admin.declareQueue(generalRequestQueue);
            admin.declareQueue(generalResultQueue);
            admin.declareQueue(generalInvalidQueue);
            admin.declareQueue(cloudInvalidQueue);
            admin.declareQueue(eventQueue);
            admin.declareQueue(deleteRequestQueue);
            admin.declareBinding(requestBinding);
            admin.declareBinding(resultBinding);
            admin.declareBinding(generalRequestBinding);
            admin.declareBinding(generalResultBinding);
            admin.declareBinding(generalInvalidBinding);
            admin.declareBinding(cloudInvalidBinding);
            admin.declareBinding(eventBinding);
            admin.declareBinding(deleteRequestBinding);
        }
    }
}
