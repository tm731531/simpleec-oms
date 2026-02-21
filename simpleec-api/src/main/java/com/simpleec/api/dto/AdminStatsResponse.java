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
public class AdminStatsResponse {
    private Integer code;
    private StatsData data;
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatsData {
        private Long merchantCount;
        private Long accountCount;
        private Long platformCount;
        private Long orderCount;
    }

    public static AdminStatsResponse success(long merchantCount, long accountCount,
                                              long platformCount, long orderCount) {
        return AdminStatsResponse.builder()
            .code(200)
            .data(StatsData.builder()
                .merchantCount(merchantCount)
                .accountCount(accountCount)
                .platformCount(platformCount)
                .orderCount(orderCount)
                .build())
            .message("success")
            .build();
    }
}
