package com.simpleec.channeljob.client;

/**
 * Client interface for calling platform APIs
 */
public interface PlatformApiClient {

    /**
     * Perform health check on channel (with authentication)
     * @param platformCode Platform identifier (momo, shopee, etc.)
     * @param token Channel's API token
     * @return HTTP status code (200, 401, 500, etc.)
     */
    int healthCheck(String platformCode, String token) throws Exception;

    /**
     * Perform platform-level health check (no auth needed)
     * @param platformCode Platform identifier
     * @return HTTP status code
     */
    int platformHealthCheck(String platformCode) throws Exception;
}
