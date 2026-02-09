package com.simpleec.api.controller;

import com.simpleec.common.model.ApiResponse;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ApiResponse<PageResult<Order>> list(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderService.list(merchantId, page, size));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable String id,
            @RequestParam String merchantId,
            @RequestParam String toStatus,
            @RequestParam(required = false) String remark) {
        orderService.updateStatus(merchantId, id, null, toStatus, "system", remark);
        return ApiResponse.ok();
    }
}
