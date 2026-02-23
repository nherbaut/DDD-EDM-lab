package fr.u.bordeaux.iut.ddd.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.Map;

public class PopulateUserLookupJpaParams implements Processor {
    @Override
    public void process(Exchange exchange) {
        String userName = exchange.getProperty("cloudcatcher.userName", String.class);
        Map<String, Object> params = new HashMap<>();
        params.put("userName", userName);
        exchange.getMessage().setHeader("CamelJpaParameters", params);
    }
}
