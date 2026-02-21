package com.simpleec.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminPageResponse<T> {
    private Integer code;
    private AdminPageData<T> data;
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AdminPageData<T> {
        private long total;
        private int page;
        private int pageSize;
        private int totalPages;
        private List<T> items;
    }

    public static <T> AdminPageResponse<T> success(Page<T> page) {
        return AdminPageResponse.<T>builder()
            .code(200)
            .data(AdminPageData.<T>builder()
                .total(page.getTotalElements())
                .page(page.getNumber() + 1)
                .pageSize(page.getSize())
                .totalPages(page.getTotalPages())
                .items(page.getContent())
                .build())
            .message("success")
            .build();
    }
}
