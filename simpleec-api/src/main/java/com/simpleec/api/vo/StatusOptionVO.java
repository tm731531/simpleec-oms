package com.simpleec.api.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 狀態選項視圖對象
 * 用於 API 響應中表示枚舉值的 code、label、description
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusOptionVO {
    private String code;
    private String label;
    private String description;
}
