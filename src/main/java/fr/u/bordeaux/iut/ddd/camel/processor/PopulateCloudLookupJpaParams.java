package fr.u.bordeaux.iut.ddd.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.Map;

public class PopulateCloudLookupJpaParams implements Processor {
    @Override
    public void process(Exchange exchange) {
        String minioObjectName = exchange.getProperty("cloudcatcher.minioObjectName", String.class);
        Map<String, Object> params = new HashMap<>();
        params.put("minioObjectName", minioObjectName);
        exchange.getMessage().setHeader("CamelJpaParameters", params);
    }
}
