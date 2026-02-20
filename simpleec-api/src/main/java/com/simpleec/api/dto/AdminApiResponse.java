package com.simpleec.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 統一的管理後台 API 響應物件
 * 所有管理後台 API 端點都使用此格式返回
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminApiResponse<T> {

    /**
     * HTTP 狀態碼 (200, 400, 404, 409, 500 等)
     */
    private Integer code;

    /**
     * 訊息文本 (success, 錯誤描述等)
     */
    private String message;

    /**
     * 響應資料體 (可為 null, 單個物件或 PageResponse 等)
     */
    private T data;

    /**
     * 便捷工廠方法：成功響應
     */
    public static <T> AdminApiResponse<T> success(T data) {
        return AdminApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .build();
    }

    /**
     * 便捷工廠方法：成功響應（無資料）
     */
    public static AdminApiResponse<Void> success() {
        return AdminApiResponse.<Void>builder()
                .code(200)
                .message("success")
                .data(null)
                .build();
    }

    /**
     * 便捷工廠方法：錯誤響應
     */
    public static <T> AdminApiResponse<T> error(Integer code, String message) {
        return AdminApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .build();
    }

    /**
     * 便捷工廠方法：驗證錯誤 (400)
     */
    public static <T> AdminApiResponse<T> badRequest(String message) {
        return error(400, message);
    }

    /**
     * 便捷工廠方法：未找到錯誤 (404)
     */
    public static <T> AdminApiResponse<T> notFound(String message) {
        return error(404, message);
    }

    /**
     * 便捷工廠方法：衝突錯誤 (409)
     */
    public static <T> AdminApiResponse<T> conflict(String message) {
        return error(409, message);
    }

    /**
     * 便捷工廠方法：伺服器錯誤 (500)
     */
    public static <T> AdminApiResponse<T> serverError(String message) {
        return error(500, message);
    }
}
