package fr.u.bordeaux.iut.ddd;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.Map;

public final class Populate {

    private Populate() {
    }

    public static Processor from(Processor processor) {
        return processor;
    }

    public static Route exchange(ValueReader reader) {
        return new Route(reader);
    }

    public static ValueReader property(String propertyName) {
        return exchange -> exchange.getProperty(propertyName);
    }

    public static <T> ValueReader property(String propertyName, Class<T> type) {
        return exchange -> exchange.getProperty(propertyName, type);
    }

    public static ValueReader headerValue(String headerName) {
        return exchange -> exchange.getMessage().getHeader(headerName);
    }

    public static <T> ValueReader headerValue(String headerName, Class<T> type) {
        return exchange -> exchange.getMessage().getHeader(headerName, type);
    }

    public static ValueTarget message(ValueTarget target) {
        return target;
    }

    public static ValueTarget header(String headerName) {
        return (exchange, value) -> exchange.getMessage().setHeader(headerName, value);
    }

    public static ValueTarget body() {
        return (exchange, value) -> exchange.getMessage().setBody(value);
    }

    public static ValueMapper identity() {
        return value -> value;
    }

    public static ValueMapper asMap(String key) {
        return value -> {
            Map<String, Object> map = new HashMap<>();
            map.put(key, value);
            return map;
        };
    }

    public static ValueMapper map(String key) {
        return asMap(key);
    }

    public static final class Route {
        private final ValueReader reader;

        private Route(ValueReader reader) {
            this.reader = reader;
        }

        public Processor to(ValueTarget target) {
            return to(target, identity());
        }

        public Processor to(ValueTarget target, ValueMapper mapper) {
            return exchange -> {
                Object value = reader.read(exchange);
                target.write(exchange, mapper.map(value));
            };
        }
    }

    @FunctionalInterface
    public interface ValueReader {
        Object read(Exchange exchange) throws Exception;
    }

    @FunctionalInterface
    public interface ValueTarget {
        void write(Exchange exchange, Object value) throws Exception;
    }

    @FunctionalInterface
    public interface ValueMapper {
        Object map(Object value);
    }
}
