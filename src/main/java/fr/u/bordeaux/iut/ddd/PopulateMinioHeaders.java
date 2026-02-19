package fr.u.bordeaux.iut.ddd;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.minio.MinioConstants;

import java.security.MessageDigest;
import java.util.Locale;

class PopulateMinioHeaders implements Processor {
    @Override
    public void process(Exchange exchange) throws Exception {
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        String userName = exchange.getProperty("cloudcatcher.userName", String.class);
        String extension = contentType.substring("image/".length()).toLowerCase(Locale.ROOT);
        if ("jpeg".equals(extension)) {
            extension = "jpg";
        }
        byte[] payload = exchange.getMessage().getBody(byte[].class);
        String hash = sha256Hex(payload);
        String objectName = "cloud/" + userName + "/" + hash + "." + extension;
        String originalFileName = exchange.getMessage().getHeader("x-file-name", String.class);
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = exchange.getMessage().getHeader("X-File-Name", String.class);
        }
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = hash + "." + extension;
        }
        exchange.setProperty("cloudcatcher.originalFileName", originalFileName);
        exchange.getMessage().setBody(payload);
        exchange.getMessage().setHeader(MinioConstants.OBJECT_NAME, objectName);
    }

    private static String sha256Hex(byte[] payload) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
