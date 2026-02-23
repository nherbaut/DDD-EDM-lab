package fr.u.bordeaux.iut.ddd.camel.processor;

public enum MimeType {
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
