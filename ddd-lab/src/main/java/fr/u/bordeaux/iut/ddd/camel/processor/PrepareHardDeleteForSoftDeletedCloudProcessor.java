package fr.u.bordeaux.iut.ddd.camel.processor;

import fr.u.bordeaux.iut.ddd.model.Cloud;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.List;

public class PrepareHardDeleteForSoftDeletedCloudProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        List<Cloud> clouds = exchange.getMessage().getBody(List.class);
        if (clouds == null || clouds.isEmpty()) {
            exchange.setProperty("cloudcatcher.hardDeleteCloud", false);
            return;
        }
        Cloud cloud = clouds.get(0);
        exchange.setProperty("cloudcatcher.cloudToHardDelete", cloud);
        exchange.setProperty("cloudcatcher.hardDeleteCloud", cloud.isDeletionRequested());
    }
}
