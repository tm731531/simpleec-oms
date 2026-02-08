package com.oneec.web.controller;

import com.oneec.common.model.ApiResponse;
import com.oneec.common.model.PageResult;
import com.oneec.core.entity.Order;
import com.oneec.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ApiResponse<PageResult<Order>> list(
            @RequestParam Long merchantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderService.list(merchantId, page, size));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable Long id,
            @RequestParam String toStatus,
            @RequestParam(required = false) String remark) {
        orderService.updateStatus(id, null, toStatus, "system", remark);
        return ApiResponse.ok();
    }
}
