package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.api.service.SellPackSyncService;
import com.simpleec.core.entity.SellPack;
import com.simpleec.core.repository.SellPackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;

/**
 * 用戶上架商品控制器
 */
@Slf4j
@RestController
@RequestMapping("/user/sellpacks")
@RequiredArgsConstructor
public class UserSellPackController {
    private final SellPackRepository sellPackRepository;
    private final SellPackSyncService sellPackSyncService;

    @GetMapping
    public ResponseEntity<UserPageResponse<SellPack>> listSellPacks(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<SellPack> result = (productId != null)
            ? sellPackRepository.findByMerchantIdAndProductId(principal.getMerchantId(), productId, PageRequest.of(page, size))
            : sellPackRepository.findByMerchantId(principal.getMerchantId(), PageRequest.of(page, size));
        return ResponseEntity.ok(UserPageResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SellPack> getSellPack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        Optional<SellPack> sp = sellPackRepository.findByIdAndMerchantId(id, principal.getMerchantId());
        return sp.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SellPack> updateSellPack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<SellPack> spOpt = sellPackRepository.findByIdAndMerchantId(id, principal.getMerchantId());
        if (spOpt.isEmpty()) return ResponseEntity.notFound().build();

        SellPack sp = spOpt.get();
        String action = (String) body.get("action");
        Object value = body.get("value");

        return switch (action) {
            case "quantity" -> {
                int oldQty = sp.getQuantity() != null ? sp.getQuantity() : 0;
                int newQty = ((Number) value).intValue();
                sp.setQuantity(newQty);
                SellPack saved = sellPackSyncService.syncUpdate(sp, "QUANTITY_UPDATE", oldQty, newQty, "UPDATE_INVENTORY");
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
            }
            case "price" -> {
                Object oldPrice = sp.getSellingPrice();
                sp.setSellingPrice(new java.math.BigDecimal(value.toString()));
                SellPack saved = sellPackSyncService.syncUpdate(sp, "PRICE_UPDATE", oldPrice, value, "UPDATE_PRICE");
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
            }
            case "listing" -> {
                String oldStatus = sp.getStatus();
                sp.setStatus((String) value);
                SellPack saved = sellPackSyncService.syncUpdate(sp, "LISTING_UPDATE", oldStatus, value, "SYNC_PACK");
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
            }
            case "image" -> {
                String oldUrl = sp.getChannelProductUrl();
                sp.setChannelProductUrl((String) value);
                SellPack saved = sellPackSyncService.syncUpdate(sp, "IMAGE_UPDATE", oldUrl, value, "SYNC_PACK");
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
            }
            case "description" -> {
                String oldTitle = sp.getTitle();
                sp.setTitle((String) value);
                SellPack saved = sellPackSyncService.syncUpdate(sp, "DESC_UPDATE", oldTitle, value, "SYNC_PACK");
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
            }
            default -> ResponseEntity.badRequest().body(null);
        };
    }
}
