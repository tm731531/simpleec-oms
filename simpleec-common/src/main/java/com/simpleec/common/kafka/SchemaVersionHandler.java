package com.simpleec.common.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Validates Kafka message schema versions against supported versions.
 * Usage in every Kafka consumer before dispatching to a handler:
 *   SchemaVersionHandler.validate(message); // throws if unsupported
 */
public final class SchemaVersionHandler {

    /** All currently supported message schema versions. Add new versions here during migration periods. */
    private static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1);

    private SchemaVersionHandler() {}

    /**
     * Validates that the message header contains a supported version.
     *
     * @throws UnsupportedSchemaVersionException if version is missing, null, or not in SUPPORTED_VERSIONS.
     *         Callers should catch this and route the message to task.dlt (not task.failed).
     */
    public static void validate(JsonNode message) throws UnsupportedSchemaVersionException {
        JsonNode header = message.path("header");
        JsonNode versionNode = header.path("version");

        if (versionNode.isMissingNode() || versionNode.isNull()) {
            throw new UnsupportedSchemaVersionException(
                "Missing 'version' field in message header. Message routed to DLT.");
        }

        int version = versionNode.asInt(-1);
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new UnsupportedSchemaVersionException(
                "Unsupported schema version: " + version +
                ". Supported: " + SUPPORTED_VERSIONS + ". Message routed to DLT.");
        }
    }
}
