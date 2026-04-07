package com.simpleec.channeljob.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;

@Slf4j
@Component
public class PlatformApiClientImpl implements PlatformApiClient {

    private final RestTemplate restTemplate;

    @Value("${cyberbiz.api.base-url:https://api.cyberbiz.co}")
    private String cyberbizBaseUrl;

    public PlatformApiClientImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ─── Channel health check (with auth) ────────────────────────────────────

    // Platform base URLs for ping (no auth required — must return 2xx/3xx)
    // Use marketing/portal sites, NOT API endpoints (API roots return 401/403/404)
    private static final java.util.Map<String, String> PLATFORM_PING_URLS = java.util.Map.of(
        "cyberbiz", "https://www.cyberbiz.co",
        "shopee",   "https://shopee.tw",
        "momo",     "https://www.momoshop.com.tw",
        "yahoo",    "https://tw.yahoo.com",
        "pchome",   "https://www.pchome.com.tw",
        "easystore","https://www.easystore.co"
    );

    @Override
    public int healthCheck(String platformCode, String token, String token2) throws Exception {
        if (token == null || token.isBlank()) {
            log.info("No token configured for channel on platform {}", platformCode);
            return 0; // unknown — credentials not set
        }
        return switch (platformCode.toLowerCase()) {
            case "cyberbiz" -> cyberbizChannelCheck(token, token2);
            // Other platforms: real clients not yet implemented → return 0 (unknown)
            default -> {
                log.info("Channel healthCheck not yet implemented for platform: {}", platformCode);
                yield 0;
            }
        };
    }

    // ─── Platform health check (no auth) ─────────────────────────────────────

    @Override
    public int platformHealthCheck(String platformCode) throws Exception {
        String pingUrl = PLATFORM_PING_URLS.get(platformCode.toLowerCase());
        if (pingUrl == null) {
            log.info("platformHealthCheck not configured for platform: {}", platformCode);
            return 0;
        }
        return simpleGet(pingUrl);
    }

    // ─── Cyberbiz ─────────────────────────────────────────────────────────────

    /**
     * GET /v1/orders?per_page=1&page=1 with HMAC-SHA256 auth.
     * 2xx / 404 = token valid; 401 / 403 = token invalid.
     */
    private int cyberbizChannelCheck(String username, String secret) {
        if (secret == null || secret.isBlank()) {
            log.info("Cyberbiz secret (token2) not configured");
            return 0;
        }
        try {
            String path = "/v1/orders";
            String url  = cyberbizBaseUrl + path + "?per_page=1&page=1";
            HttpHeaders headers = buildCyberbizHmacHeaders(username, secret, "GET", path);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return resp.getStatusCode().value();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().value();
        } catch (ResourceAccessException e) {
            log.warn("Cyberbiz channel check connection error: {}", e.getMessage());
            return 503;
        } catch (Exception e) {
            log.error("Cyberbiz channel check error", e);
            return 500;
        }
    }

    // ─── Generic GET (platform-level, no auth) ────────────────────────────────

    private int simpleGet(String url) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            return resp.getStatusCode().value();
        } catch (HttpStatusCodeException e) {
            // Any HTTP response means the platform is reachable
            return e.getStatusCode().value();
        } catch (ResourceAccessException e) {
            log.warn("Platform unreachable: {} — {}", url, e.getMessage());
            return 503;
        } catch (Exception e) {
            log.error("Platform check error for {}", url, e);
            return 500;
        }
    }

    // ─── Cyberbiz HMAC-SHA256 header builder ─────────────────────────────────

    private HttpHeaders buildCyberbizHmacHeaders(String username, String secret, String method, String path) throws Exception {
        HttpHeaders headers = new HttpHeaders();

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String xDate = sdf.format(new Date());
        headers.set("X-Date", xDate);

        String requestLine  = method + " " + path + " HTTP/1.1";
        String stringToSign = "x-date: " + xDate + "\n" + requestLine;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

        headers.set("Authorization", String.format(
            "hmac username=\"%s\", algorithm=\"hmac-sha256\", headers=\"x-date request-line\", signature=\"%s\"",
            username, signature));

        return headers;
    }
}
