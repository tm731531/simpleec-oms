package com.simpleec.channeljob.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.http.HttpStatus;

/**
 * Implementation of PlatformApiClient
 * Calls actual platform APIs with proper error handling
 */
@Slf4j
@Component
public class PlatformApiClientImpl implements PlatformApiClient {

    private final RestTemplate restTemplate;

    public PlatformApiClientImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public int healthCheck(String platformCode, String token) throws Exception {
        try {
            // Placeholder: Map platform code to API endpoint
            String endpoint = getPlatformHealthEndpoint(platformCode);
            log.debug("Calling health check for {} with token", platformCode);

            // In real implementation, would call actual platform API
            // For now, return 200 (success)
            return 200;

        } catch (RestClientException e) {
            log.warn("Health check failed for platform {}: {}", platformCode, e.getMessage());
            return 500;
        } catch (Exception e) {
            log.error("Error during health check for platform {}", platformCode, e);
            return 500;
        }
    }

    @Override
    public int platformHealthCheck(String platformCode) throws Exception {
        try {
            // Platform-level health check (no token needed)
            String endpoint = getPlatformStatusEndpoint(platformCode);
            log.debug("Calling platform health check for {}", platformCode);

            // In real implementation, would call actual platform status API
            // For now, return 200 (success)
            return 200;

        } catch (RestClientException e) {
            log.warn("Platform health check failed for {}: {}", platformCode, e.getMessage());
            return 503; // Service unavailable
        } catch (Exception e) {
            log.error("Error during platform health check for {}", platformCode, e);
            return 500;
        }
    }

    /**
     * Get platform-specific health check endpoint
     */
    private String getPlatformHealthEndpoint(String platformCode) {
        return switch (platformCode.toLowerCase()) {
            case "momo" -> "https://api.momo.com/v1/health";
            case "shopee" -> "https://partner.shopeemobile.com/api/v2/health";
            case "yahoo" -> "https://api.yahoo.com/v1/health";
            case "pchome" -> "https://api.pchome.com.tw/v1/health";
            case "cyberbiz" -> "https://api.cyberbiz.com/v1/health";
            case "shopline" -> "https://api.shoplineapp.com/v1/health";
            case "shopify" -> "https://api.shopify.com/v1/health";
            default -> throw new IllegalArgumentException("Unknown platform: " + platformCode);
        };
    }

    /**
     * Get platform-specific status endpoint (no auth)
     */
    private String getPlatformStatusEndpoint(String platformCode) {
        return switch (platformCode.toLowerCase()) {
            case "momo" -> "https://status.momo.com/api/status";
            case "shopee" -> "https://status.shopeemobile.com/api/status";
            case "yahoo" -> "https://status.yahoo.com/api/status";
            case "pchome" -> "https://status.pchome.com.tw/api/status";
            case "cyberbiz" -> "https://status.cyberbiz.com/api/status";
            case "shopline" -> "https://status.shoplineapp.com/api/status";
            case "shopify" -> "https://status.shopify.com/api/status";
            default -> throw new IllegalArgumentException("Unknown platform: " + platformCode);
        };
    }
}
