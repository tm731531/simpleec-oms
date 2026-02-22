package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.repository.ReturnOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用戶退貨管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/user/refunds")
@RequiredArgsConstructor
public class UserRefundController {
    private final ReturnOrderRepository returnOrderRepository;

    @GetMapping
    public ResponseEntity<UserPageResponse<ReturnOrder>> listRefunds(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<ReturnOrder> result;
        if (orderId != null) {
            List<ReturnOrder> list = returnOrderRepository.findByOrderId(orderId)
                .stream().filter(r -> principal.getMerchantId().equals(r.getMerchantId())).toList();
            return ResponseEntity.ok(UserPageResponse.<ReturnOrder>builder()
                .data(list)
                .pagination(Map.of("page", 1, "pageSize", list.size(), "total", (long) list.size(), "pages", 1))
                .build());
        }
        result = returnOrderRepository.findByMerchantId(principal.getMerchantId(), PageRequest.of(page, size));
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReturnOrder> getRefund(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        Optional<ReturnOrder> ro = returnOrderRepository.findById(id);
        if (ro.isEmpty() || !principal.getMerchantId().equals(ro.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ro.get());
    }

    @PostMapping
    public ResponseEntity<ReturnOrder> createRefund(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> body) {
        // Generate ReturnOrder ID: merchant(4) + yyyyMMddHHmmss(14) + random(2) = 20 chars
        String merchantId = principal.getMerchantId();
        String merchantPrefix = merchantId != null && merchantId.length() >= 4 ? merchantId.substring(0, 4) : "XXXX";
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomPart = NanoIdUtil.generate().substring(0, 2);
        String returnOrderId = merchantPrefix + timestamp + randomPart;

        ReturnOrder ro = ReturnOrder.builder()
            .id(returnOrderId)
            .merchantId(principal.getMerchantId())
            .orderId((String) body.get("orderId"))
            .reason((String) body.get("reason"))
            .refundAmount(body.get("amount") != null ? new BigDecimal(body.get("amount").toString()) : null)
            .returnStatus(ReturnStatusEnum.PENDING)
            .requestedAt(LocalDateTime.now())
            .build();
        ReturnOrder saved = returnOrderRepository.save(ro);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ReturnOrder> updateRefund(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<ReturnOrder> roOpt = returnOrderRepository.findById(id);
        if (roOpt.isEmpty() || !principal.getMerchantId().equals(roOpt.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        ReturnOrder ro = roOpt.get();
        String action = (String) body.get("action");
        if ("approve".equals(action)) {
            ro.setReturnStatus(ReturnStatusEnum.APPROVED);
        } else if ("reject".equals(action)) {
            ro.setReturnStatus(ReturnStatusEnum.REJECTED);
        } else {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(returnOrderRepository.save(ro));
    }
}
