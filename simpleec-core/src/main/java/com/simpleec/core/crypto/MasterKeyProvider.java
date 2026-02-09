package com.simpleec.core.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 從 global_config 讀取三次 Base64 編碼的 master key，解碼後快取。
 * 用 JdbcTemplate（不經 MyBatis）避免循環依賴。
 */
@Slf4j
@Component
public class MasterKeyProvider {

    private static final String CONFIG_KEY = "encryption_master_key";

    private final JdbcTemplate jdbcTemplate;
    private volatile byte[] masterKey;

    public MasterKeyProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public byte[] getMasterKey() {
        if (masterKey == null) {
            synchronized (this) {
                if (masterKey == null) {
                    loadMasterKey();
                }
            }
        }
        return masterKey;
    }

    public synchronized void refreshMasterKey() {
        masterKey = null;
        loadMasterKey();
    }

    private void loadMasterKey() {
        String tripleBase64 = jdbcTemplate.queryForObject(
                "SELECT data FROM global_config WHERE id = ?",
                String.class,
                CONFIG_KEY
        );
        if (tripleBase64 == null || tripleBase64.isBlank()) {
            throw new IllegalStateException("encryption_master_key not found in global_config");
        }
        // 三次 Base64 解碼
        byte[] decoded = Base64.getDecoder().decode(tripleBase64.trim());
        decoded = Base64.getDecoder().decode(decoded);
        decoded = Base64.getDecoder().decode(decoded);
        this.masterKey = decoded;
        log.info("Master encryption key loaded ({} bytes)", decoded.length);
    }
}
