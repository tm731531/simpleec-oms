package com.simpleec.job.retry.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
public class RetryPolicyService {

    private static final int DEFAULT_MAX_RETRY = 3;

    private static final Set<String> RETRYABLE_ACTIONS = Set.of(
        "FETCH_ORDERS", "FETCH_PRODUCTS", "GET_QUANTITY",
        "FETCH_RETURNS", "FETCH_STATISTICS", "SYNC_CATEGORIES", "SYNC_BRANDS"
    );

    public int getMaxRetry(String action) {
        return DEFAULT_MAX_RETRY;
    }

    public boolean isRetryable(String action) {
        return action != null && RETRYABLE_ACTIONS.contains(action);
    }

    public Duration getDelay(int retryCount) {
        // Exponential backoff: 30s, 60s, 120s
        long seconds = 30L * (1L << retryCount);
        return Duration.ofSeconds(Math.min(seconds, 300));
    }
}
