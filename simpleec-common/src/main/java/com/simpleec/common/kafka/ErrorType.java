package com.simpleec.common.kafka;

/**
 * Error type classification for retry decisions.
 *
 * Determines whether a failed task should be retried, how many times,
 * and with what backoff strategy.
 */
public enum ErrorType {
    CLIENT_ERROR_4XX(false, 0),      // HTTP 4xx — do not retry (client error)
    SERVER_ERROR_5XX(true, 3),       // HTTP 5xx — retry up to 3 times
    NETWORK_ERROR(true, 5),          // Connection refused, timeout — retry up to 5 times
    FORMAT_ERROR(false, 0);          // Unparseable message, schema error — do not retry

    private final boolean retryable;
    private final int maxRetries;

    ErrorType(boolean retryable, int maxRetries) {
        this.retryable = retryable;
        this.maxRetries = maxRetries;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Parse error type from string, returning null for unknown values.
     */
    public static ErrorType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ErrorType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
