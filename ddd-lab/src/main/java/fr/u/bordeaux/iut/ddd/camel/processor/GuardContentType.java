package fr.u.bordeaux.iut.ddd.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Locale;

public class GuardContentType implements Processor {

    private final String contentType;

    private GuardContentType(String contentType) {
        this.contentType = contentType;
    }

    public static GuardContentType gardMime(MimeType mimeType) {
        return new GuardContentType(mimeType.getMimeType());
    }


    @Override
    public void process(Exchange exchange) throws Exception {
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(this.contentType)) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 415);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain");
            exchange.getMessage().setBody("Unsupported Media Type\n");
            exchange.setRouteStop(true);

            return;
        } else {
            String fileExtension = switch (contentType) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                default -> "unknown";
            };


            exchange.getMessage().setHeader("fileExtension", fileExtension);
        }
    }
}
