package fr.u.bordeaux.iut.ddd.camel.processor;

import fr.u.bordeaux.iut.ddd.model.Cloud;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.Map;

public class PopulateCloudDeleteJpaParams implements Processor {
    @Override
    public void process(Exchange exchange) {
        Cloud cloud = exchange.getProperty("cloudcatcher.cloudToHardDelete", Cloud.class);
        if (cloud == null) {
            exchange.setProperty("cloudcatcher.hardDeleteCloud", false);
            return;
        }
        Map<String, Object> jpaParams = new HashMap<>();
        jpaParams.put("cloudId", cloud.getId());
        exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
    }
}
