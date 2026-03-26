package com.simpleec.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Verifies HMAC-SHA256 webhook signatures for each supported platform.
 * Returns false (instead of throwing) so callers can return 200 OK to prevent retry storms.
 */
@Component
public class WebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookVerifier.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${webhook.secrets.shopify:}")
    private String shopifySecret;

    @Value("${webhook.secrets.shopee:}")
    private String shopeeSecret;

    @Value("${webhook.secrets.easystore:}")
    private String easystoreSecret;

    /**
     * Shopify: X-Shopify-Hmac-Sha256 header contains Base64(HMAC-SHA256(body, secret))
     */
    public boolean verifyShopify(String rawBody, String signatureHeader) {
        return verifyBase64Hmac(rawBody, signatureHeader, shopifySecret, "Shopify");
    }

    /**
     * Shopee: X-Shopee-Signature header contains hex(HMAC-SHA256(url + "|" + timestamp + "|" + body, secret))
     * Since we don't have the full URL and timestamp here, verify against body only.
     * Read Shopee's webhook docs and adjust the message to sign accordingly.
     */
    public boolean verifyShopee(String rawBody, String signatureHeader) {
        return verifyHexHmac(rawBody, signatureHeader, shopeeSecret, "Shopee");
    }

    /**
     * Easystore: X-Easystore-Hmac-Sha256 header contains Base64(HMAC-SHA256(body, secret))
     */
    public boolean verifyEasystore(String rawBody, String signatureHeader) {
        return verifyBase64Hmac(rawBody, signatureHeader, easystoreSecret, "Easystore");
    }

    private boolean verifyBase64Hmac(String body, String signature, String secret, String platform) {
        if (isBlank(secret)) {
            log.warn("[{}] Webhook secret not configured — skipping signature verification", platform);
            return true; // allow through if not configured (dev mode)
        }
        if (isBlank(signature)) {
            log.warn("[{}] Missing signature header", platform);
            return false;
        }
        try {
            String computed = Base64.getEncoder().encodeToString(computeHmac(body, secret));
            boolean valid = constantTimeEquals(computed, signature.trim());
            if (!valid) log.warn("[{}] Signature mismatch", platform);
            return valid;
        } catch (Exception e) {
            log.error("[{}] Signature verification error", platform, e);
            return false;
        }
    }

    private boolean verifyHexHmac(String body, String signature, String secret, String platform) {
        if (isBlank(secret)) {
            log.warn("[{}] Webhook secret not configured — skipping signature verification", platform);
            return true;
        }
        if (isBlank(signature)) {
            log.warn("[{}] Missing signature header", platform);
            return false;
        }
        try {
            String computed = HexFormat.of().formatHex(computeHmac(body, secret));
            boolean valid = constantTimeEquals(computed, signature.trim().toLowerCase());
            if (!valid) log.warn("[{}] Signature mismatch", platform);
            return valid;
        } catch (Exception e) {
            log.error("[{}] Signature verification error", platform, e);
            return false;
        }
    }

    private byte[] computeHmac(String data, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
