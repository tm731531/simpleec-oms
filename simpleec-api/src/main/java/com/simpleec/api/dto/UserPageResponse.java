package com.simpleec.api.dto;

import lombok.*;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.Map;

/**
 * 用戶API分頁響應 - 前端期望格式
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPageResponse<T> {
    private List<T> data;
    private Map<String, Object> pagination;

    public static <T> UserPageResponse<T> from(Page<T> page) {
        return UserPageResponse.<T>builder()
            .data(page.getContent())
            .pagination(Map.of(
                "page", page.getNumber() + 1,
                "pageSize", page.getSize(),
                "total", page.getTotalElements(),
                "pages", page.getTotalPages()
            ))
            .build();
    }
}
