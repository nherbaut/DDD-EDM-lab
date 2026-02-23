package fr.u.bordeaux.iut.ddd.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.Map;

public class PopulateJpaParametersForUserProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        String userName = exchange.getProperty("cloudcatcher.userName", String.class);
        String minioObjectName = exchange.getMessage().getHeader("CamelMinioObjectName", String.class);
        String minioETag = exchange.getMessage().getHeader("CamelMinioETag", String.class);
        exchange.setProperty("cloudcatcher.minioObjectName", minioObjectName);
        exchange.setProperty("cloudcatcher.minioETag", minioETag);
        Map<String, Object> jpaParams = new HashMap<>();
        jpaParams.put("userName", userName);
        exchange.getMessage().setHeader("CamelJpaParameters", jpaParams);
    }
}
