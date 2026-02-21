package com.simpleec.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminResponse<T> {
    private Integer code;
    private T data;
    private String message;

    public static <T> AdminResponse<T> success(T data) {
        return AdminResponse.<T>builder()
            .code(200)
            .data(data)
            .message("success")
            .build();
    }

    public static AdminResponse<Void> success() {
        return AdminResponse.<Void>builder()
            .code(200)
            .message("success")
            .build();
    }
}
