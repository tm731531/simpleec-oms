package com.simpleec.core.kafka;

public final class SchemaVersionHandler {

    public static final int CURRENT_VERSION = 1;

    private SchemaVersionHandler() {}

    /**
     * Normalize schema version. Jackson deserializes missing int as 0; treat 0 as version 1.
     */
    public static int normalize(int schemaVersion) {
        return schemaVersion <= 0 ? 1 : schemaVersion;
    }

    /**
     * Check if a message version is supported by this consumer.
     */
    public static boolean isSupported(int schemaVersion) {
        int v = normalize(schemaVersion);
        return v >= 1 && v <= CURRENT_VERSION;
    }
}
