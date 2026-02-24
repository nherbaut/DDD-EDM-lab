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

import java.util.Map;

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
            Queue generalDeniedQueue = new Queue("cloud.general.classifier.denied", false);
            Queue cloudCompletedQueue = new Queue("cloud.cloud-classifier.completed", false);
            Queue cloudCatcherGeneralDeniedQueue = new Queue("cloud.catcher.general.denied.events", false);
            Queue cloudCatcherCloudCompletedQueue = new Queue("cloud.catcher.cloud.completed.events", false);
            Queue cloudCatcherQuotaUpdatedQueue = new Queue("cloud.catcher.quota.updated.events", false);
            Queue cloudAccountingUserCreatedQueue = new Queue("cloud.accounting.user.created.events", false);
            Queue cloudAccountingGeneralRequestQueue = new Queue("cloud.accounting.general.classifier.request.events", false);
            Queue cloudAccountingCloudCompletedQueue = new Queue("cloud.accounting.cloud.classifier.completed.events", false);
            Queue cloudAccountingQuotaRequestQueue = new Queue("cloud.accounting.quota.request.events", false);
            Queue generalInvalidQueue = new Queue("cloud.general.classifier.invalid", false);
            Queue cloudInvalidQueue = new Queue("cloud.cloud-classifier.invalid", false);
            Queue auditQueue = new Queue("cloud.audit.events", false, false, false, Map.of("x-message-ttl", 14_400_000));
            Queue eventQueue = new Queue("cloud.minio.events", false);
            Queue deleteRequestQueue = new Queue("cloud.delete.requests", false);
            Queue userCreatedQueue = new Queue("cloud.user.created.events", false);
            Binding requestBinding = BindingBuilder.bind(requestQueue).to(exchangeDef).with("cloud.cloud-classifier.request");
            Binding resultBinding = BindingBuilder.bind(resultQueue).to(exchangeDef).with("cloud.cloud-classifier.results");
            Binding generalRequestBinding = BindingBuilder.bind(generalRequestQueue).to(exchangeDef).with("classification.quota.reserved.v1");
            Binding generalResultBinding = BindingBuilder.bind(generalResultQueue).to(exchangeDef).with("classification.general.authorized.v1");
            Binding generalDeniedBinding = BindingBuilder.bind(generalDeniedQueue).to(exchangeDef).with("classification.general.denied.v1");
            Binding cloudCompletedBinding = BindingBuilder.bind(cloudCompletedQueue).to(exchangeDef).with("classification.cloud.completed.v1");
            Binding cloudCatcherGeneralDeniedBinding = BindingBuilder.bind(cloudCatcherGeneralDeniedQueue).to(exchangeDef).with("classification.general.denied.v1");
            Binding cloudCatcherCloudCompletedBinding = BindingBuilder.bind(cloudCatcherCloudCompletedQueue).to(exchangeDef).with("classification.cloud.completed.v1");
            Binding cloudCatcherQuotaUpdatedBinding = BindingBuilder.bind(cloudCatcherQuotaUpdatedQueue).to(exchangeDef).with("accounting.quota.updated.v1");
            Binding cloudAccountingUserCreatedBinding = BindingBuilder.bind(cloudAccountingUserCreatedQueue).to(exchangeDef).with("admin.user.new");
            Binding cloudAccountingGeneralRequestBinding = BindingBuilder.bind(cloudAccountingGeneralRequestQueue).to(exchangeDef).with("cloud.general.classifier.request");
            Binding cloudAccountingCloudCompletedBinding = BindingBuilder.bind(cloudAccountingCloudCompletedQueue).to(exchangeDef).with("classification.cloud.completed.v1");
            Binding cloudAccountingQuotaRequestBinding = BindingBuilder.bind(cloudAccountingQuotaRequestQueue).to(exchangeDef).with("accounting.quota.request.v1");
            Binding generalInvalidBinding = BindingBuilder.bind(generalInvalidQueue).to(exchangeDef).with("cloud.general.classifier.invalid");
            Binding cloudInvalidBinding = BindingBuilder.bind(cloudInvalidQueue).to(exchangeDef).with("cloud.cloud-classifier.invalid");
            Binding auditRequestBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.cloud-classifier.request");
            Binding auditResultBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.cloud-classifier.results");
            Binding auditGeneralRequestBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("classification.quota.reserved.v1");
            Binding auditGeneralResultBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("classification.general.authorized.v1");
            Binding auditGeneralDeniedBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("classification.general.denied.v1");
            Binding auditCloudCompletedBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("classification.cloud.completed.v1");
            Binding auditQuotaUpdatedBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("accounting.quota.updated.v1");
            Binding auditUserCreatedBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("admin.user.new");
            Binding auditAccountingGeneralRequestBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.general.classifier.request");
            Binding auditAccountingQuotaRequestBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("accounting.quota.request.v1");
            Binding auditGeneralInvalidBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.general.classifier.invalid");
            Binding auditCloudInvalidBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.cloud-classifier.invalid");
            Binding auditEventBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.minio.event");
            Binding auditDeleteRequestBinding = BindingBuilder.bind(auditQueue).to(exchangeDef).with("cloud.delete.request");
            Binding eventBinding = BindingBuilder.bind(eventQueue).to(exchangeDef).with("cloud.minio.event");
            Binding deleteRequestBinding = BindingBuilder.bind(deleteRequestQueue).to(exchangeDef).with("cloud.delete.request");
            Binding userCreatedBinding = BindingBuilder.bind(userCreatedQueue).to(exchangeDef).with("admin.user.new");

            admin.declareExchange(exchangeDef);
            admin.declareQueue(requestQueue);
            admin.declareQueue(resultQueue);
            admin.declareQueue(generalRequestQueue);
            admin.declareQueue(generalResultQueue);
            admin.declareQueue(generalDeniedQueue);
            admin.declareQueue(cloudCompletedQueue);
            admin.declareQueue(cloudCatcherGeneralDeniedQueue);
            admin.declareQueue(cloudCatcherCloudCompletedQueue);
            admin.declareQueue(cloudCatcherQuotaUpdatedQueue);
            admin.declareQueue(cloudAccountingUserCreatedQueue);
            admin.declareQueue(cloudAccountingGeneralRequestQueue);
            admin.declareQueue(cloudAccountingCloudCompletedQueue);
            admin.declareQueue(cloudAccountingQuotaRequestQueue);
            admin.declareQueue(generalInvalidQueue);
            admin.declareQueue(cloudInvalidQueue);
            admin.declareQueue(auditQueue);
            admin.declareQueue(eventQueue);
            admin.declareQueue(deleteRequestQueue);
            admin.declareQueue(userCreatedQueue);
            admin.declareBinding(requestBinding);
            admin.declareBinding(resultBinding);
            admin.declareBinding(generalRequestBinding);
            admin.declareBinding(generalResultBinding);
            admin.declareBinding(generalDeniedBinding);
            admin.declareBinding(cloudCompletedBinding);
            admin.declareBinding(cloudCatcherGeneralDeniedBinding);
            admin.declareBinding(cloudCatcherCloudCompletedBinding);
            admin.declareBinding(cloudCatcherQuotaUpdatedBinding);
            admin.declareBinding(cloudAccountingUserCreatedBinding);
            admin.declareBinding(cloudAccountingGeneralRequestBinding);
            admin.declareBinding(cloudAccountingCloudCompletedBinding);
            admin.declareBinding(cloudAccountingQuotaRequestBinding);
            admin.declareBinding(generalInvalidBinding);
            admin.declareBinding(cloudInvalidBinding);
            admin.declareBinding(auditRequestBinding);
            admin.declareBinding(auditResultBinding);
            admin.declareBinding(auditGeneralRequestBinding);
            admin.declareBinding(auditGeneralResultBinding);
            admin.declareBinding(auditGeneralDeniedBinding);
            admin.declareBinding(auditCloudCompletedBinding);
            admin.declareBinding(auditQuotaUpdatedBinding);
            admin.declareBinding(auditUserCreatedBinding);
            admin.declareBinding(auditAccountingGeneralRequestBinding);
            admin.declareBinding(auditAccountingQuotaRequestBinding);
            admin.declareBinding(auditGeneralInvalidBinding);
            admin.declareBinding(auditCloudInvalidBinding);
            admin.declareBinding(auditEventBinding);
            admin.declareBinding(auditDeleteRequestBinding);
            admin.declareBinding(eventBinding);
            admin.declareBinding(deleteRequestBinding);
            admin.declareBinding(userCreatedBinding);
        }
    }
}
