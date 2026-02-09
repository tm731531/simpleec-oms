package com.simpleec.core.crypto;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler — 透明加解密 PII 欄位。
 * MyBatis 自行實例化 TypeHandler（不經 Spring），
 * 所以用靜態 holder 持有 Spring 注入的 AesGcmEncryptor。
 */
@Slf4j
public class EncryptedFieldTypeHandler extends BaseTypeHandler<String> {

    private static AesGcmEncryptor encryptor;

    /**
     * 由 EncryptionConfig @PostConstruct 注入。
     */
    public static void setEncryptor(AesGcmEncryptor enc) {
        encryptor = enc;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                     String parameter, JdbcType jdbcType) throws SQLException {
        String merchantId = EncryptionContext.getMerchantId();
        if (merchantId == null || encryptor == null) {
            log.warn("EncryptionContext not set or encryptor unavailable, writing plaintext");
            ps.setString(i, parameter);
            return;
        }
        ps.setString(i, encryptor.encrypt(parameter, merchantId));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decryptIfNeeded(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decryptIfNeeded(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decryptIfNeeded(cs.getString(columnIndex));
    }

    private String decryptIfNeeded(String value) {
        if (value == null) {
            return null;
        }
        String merchantId = EncryptionContext.getMerchantId();
        if (merchantId == null || encryptor == null) {
            log.warn("EncryptionContext not set or encryptor unavailable, returning raw value");
            return value;
        }
        try {
            return encryptor.decrypt(value, merchantId);
        } catch (Exception e) {
            // 遷移期：DB 中可能還有明文資料，解密失敗時 fallback 回傳原文
            log.debug("Decrypt failed (possibly plaintext data), returning raw value: {}", e.getMessage());
            return value;
        }
    }
}
