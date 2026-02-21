package fr.u.bordeaux.iut.ddd.camel.processor;

import fr.u.bordeaux.iut.ddd.model.Cloud;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
public class HardDeleteCloudEntityProcessor implements Processor {

    @Inject
    EntityManager entityManager;

    @Override
    @Transactional
    public void process(Exchange exchange) {
        Cloud cloud = exchange.getProperty("cloudcatcher.cloudToHardDelete", Cloud.class);
        if (cloud == null || cloud.getId() == null) {
            return;
        }
        Cloud managed = entityManager.find(Cloud.class, cloud.getId());
        if (managed != null) {
            entityManager.remove(managed);
        }
    }
}
