package com.simpleec.channeljob.client;

/**
 * Client interface for calling platform APIs
 */
public interface PlatformApiClient {

    /**
     * Perform health check on channel (with authentication)
     * @param platformCode Platform identifier (momo, shopee, etc.)
     * @param token  Primary credential (Cyberbiz: username; others: API token)
     * @param token2 Secondary credential (Cyberbiz: secret; others: null)
     * @return HTTP status code (200, 401, 403, 500, etc.)
     */
    int healthCheck(String platformCode, String token, String token2) throws Exception;

    /**
     * Perform platform-level health check (no auth needed)
     * @param platformCode Platform identifier
     * @return HTTP status code
     */
    int platformHealthCheck(String platformCode) throws Exception;
}
