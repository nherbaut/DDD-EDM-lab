package fr.u.bordeaux.iut.ddd;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.MimeType;

import java.util.Locale;

class GuardContentType implements Processor {

    private final String contentType;

    private GuardContentType(String contentType) {
        this.contentType = contentType;
    }

    enum MimeType {
        JPG("image", "jpeg"),
        PNG("image", "png"),
        IMAGE("image", "");

        private final String type;
        private final String subType;

        private MimeType(String type, String subType) {
            this.type = type;
            this.subType = subType;
        }

        public String getType() {
            return this.type;
        }

        public String getSubType() {
            return this.subType;
        }

        public String getMimeType() {
            return this.type + "/" + this.subType;
        }

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
        }
    }
}
