package com.simpleec.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 分頁響應包裝器
 * 用於返回分頁查詢結果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResponse<T> {

    /**
     * 總共多少筆記錄
     */
    private Long total;

    /**
     * 當前第幾頁（1-based）
     */
    private Integer page;

    /**
     * 每頁記錄數
     */
    private Integer pageSize;

    /**
     * 總共多少頁
     */
    private Integer totalPages;

    /**
     * 本頁的資料項目
     */
    private List<T> items;

    /**
     * 便捷工廠方法：從 Spring Page 物件轉換
     * 注意：Spring Page 的 page 是 0-based，我們轉換為 1-based
     */
    public static <T> PageResponse<T> fromPage(Page<T> page) {
        return PageResponse.<T>builder()
                .total(page.getTotalElements())
                .page(page.getNumber() + 1)  // 0-based 轉為 1-based
                .pageSize(page.getSize())
                .totalPages(page.getTotalPages())
                .items(page.getContent())
                .build();
    }

    /**
     * 便捷工廠方法：手動建立
     */
    public static <T> PageResponse<T> of(Long total, Integer page, Integer pageSize,
                                          Integer totalPages, List<T> items) {
        return PageResponse.<T>builder()
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .totalPages(totalPages)
                .items(items)
                .build();
    }
}
