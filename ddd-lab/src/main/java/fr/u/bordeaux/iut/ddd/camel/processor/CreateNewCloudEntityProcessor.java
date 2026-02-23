package fr.u.bordeaux.iut.ddd.camel.processor;

import fr.u.bordeaux.iut.ddd.model.Cloud;
import fr.u.bordeaux.iut.ddd.model.User;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class CreateNewCloudEntityProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        User user = exchange.getProperty("cloudcatcher.userEntity", User.class);
        String minioObjectName = exchange.getProperty("cloudcatcher.minioObjectName", String.class);
        String minioETag = exchange.getProperty("cloudcatcher.minioETag", String.class);
        String originalFileName = exchange.getProperty("cloudcatcher.originalFileName", String.class);
        Cloud cloud = new Cloud(minioObjectName, minioETag, user, originalFileName);
        exchange.getMessage().setBody(cloud);
    }
}
