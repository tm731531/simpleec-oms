package com.simpleec.common.kafka;

/**
 * Thrown when a Kafka message has an unsupported schema version.
 * Consumers should catch this and route the message to task.dlt.
 */
public class UnsupportedSchemaVersionException extends Exception {
    public UnsupportedSchemaVersionException(String message) {
        super(message);
    }
}
