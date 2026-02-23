package com.simpleec.api.controller;

import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.service.ReturnOrderService;
import com.simpleec.common.enums.ReturnStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 退貨 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnOrderService returnOrderService;

    /**
     * 查詢退貨列表（分頁）
     * GET /api/returns?merchantId=M001&page=0&size=10
     */
    @GetMapping
    public ResponseEntity<Page<ReturnOrder>> listReturns(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        Pageable pageable = PageRequest.of(page, size);

        Page<ReturnOrder> returns;
        if (status != null) {
            try {
                ReturnStatusEnum statusEnum = ReturnStatusEnum.fromCode(status);
                returns = returnOrderService.findByStatus(merchantId, statusEnum, pageable);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            returns = returnOrderService.findByMerchantId(merchantId, pageable);
        }

        log.info("Listed {} returns for merchant {}", returns.getTotalElements(), merchantId);
        return ResponseEntity.ok(returns);
    }

    /**
     * 查詢單筆退貨
     * GET /api/returns/{returnId}
     */
    @GetMapping("/{returnId}")
    public ResponseEntity<ReturnOrder> getReturn(@PathVariable String returnId) {
        Optional<ReturnOrder> returnOrder = returnOrderService.findById(returnId);

        if (returnOrder.isPresent()) {
            log.info("Retrieved return: {}", returnId);
            return ResponseEntity.ok(returnOrder.get());
        } else {
            log.warn("Return not found: {}", returnId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 查詢訂單的退貨
     * GET /api/returns/order/{orderId}
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<ReturnOrder>> getReturnsByOrder(@PathVariable String orderId) {
        List<ReturnOrder> returns = returnOrderService.findByOrderId(orderId);

        log.info("Retrieved {} returns for order {}", returns.size(), orderId);
        return ResponseEntity.ok(returns);
    }

    /**
     * 建立退貨
     * POST /api/returns
     */
    @PostMapping
    public ResponseEntity<ReturnOrder> createReturn(@RequestBody ReturnOrder returnOrder) {
        try {
            ReturnOrder created = returnOrderService.createReturn(returnOrder);
            log.info("Created return: {} for order {}", created.getId(), created.getOrderId());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Error creating return", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 更新退貨狀態
     * PATCH /api/returns/{returnId}
     */
    @PatchMapping("/{returnId}")
    public ResponseEntity<ReturnOrder> updateReturn(
            @PathVariable String returnId,
            @RequestBody ReturnOrder returnUpdate) {

        Optional<ReturnOrder> existing = returnOrderService.findById(returnId);

        if (existing.isPresent()) {
            ReturnOrder returnOrder = existing.get();

            if (returnUpdate.getReturnStatus() != null) {
                returnOrder.setReturnStatus(returnUpdate.getReturnStatus());
            }
            if (returnUpdate.getReason() != null) {
                returnOrder.setReason(returnUpdate.getReason());
            }

            ReturnOrder updated = returnOrderService.updateReturn(returnOrder);
            log.info("Updated return: {}", returnId);
            return ResponseEntity.ok(updated);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 批准退貨
     * POST /api/returns/{returnId}/approve
     */
    @PostMapping("/{returnId}/approve")
    public ResponseEntity<ReturnOrder> approveReturn(@PathVariable String returnId) {
        Optional<ReturnOrder> existing = returnOrderService.findById(returnId);

        if (existing.isPresent()) {
            ReturnOrder returnOrder = existing.get();
            returnOrder.setReturnStatus(ReturnStatusEnum.APPROVED);
            ReturnOrder updated = returnOrderService.updateReturn(returnOrder);

            log.info("Approved return: {}", returnId);
            return ResponseEntity.ok(updated);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 拒絕退貨
     * POST /api/returns/{returnId}/reject
     */
    @PostMapping("/{returnId}/reject")
    public ResponseEntity<ReturnOrder> rejectReturn(@PathVariable String returnId) {
        Optional<ReturnOrder> existing = returnOrderService.findById(returnId);

        if (existing.isPresent()) {
            ReturnOrder returnOrder = existing.get();
            returnOrder.setReturnStatus(ReturnStatusEnum.REJECTED);
            ReturnOrder updated = returnOrderService.updateReturn(returnOrder);

            log.info("Rejected return: {}", returnId);
            return ResponseEntity.ok(updated);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
