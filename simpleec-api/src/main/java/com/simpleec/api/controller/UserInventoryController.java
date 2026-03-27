package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Product;
import com.simpleec.core.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * REST controller for inventory management.
 *
 * Inventory is a direct view of product.quantity vs product.safety_quantity.
 * No caching — queried directly from DB (low-frequency, per-day usage).
 * All endpoints are JWT-protected via the global security filter chain.
 */
@Slf4j
@RestController
@RequestMapping("/api/user/inventory")
@RequiredArgsConstructor
public class UserInventoryController {

    private final ProductRepository productRepository;

    // -------------------------------------------------------------------------
    // Inner record types — Request DTOs
    // -------------------------------------------------------------------------

    /**
     * Request body for manually adjusting inventory quantity.
     */
    record AdjustInventoryRequest(
            Integer quantity     // new absolute quantity to set
    ) {}

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/user/inventory
     * Returns all products for the merchant with their current quantity and
     * safety_quantity so the client can render a stock overview dashboard.
     */
    @GetMapping
    public ResponseEntity<UserPageResponse<Product>> listInventory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Product> products = productRepository.findByMerchantId(
                principal.getMerchantId(), pageable);
        return ResponseEntity.ok(UserPageResponse.from(products));
    }

    /**
     * GET /api/user/inventory/low-stock
     * Returns products whose quantity is at or below their safety_quantity.
     *
     * The query uses a fixed safety_quantity sentinel of Integer.MAX_VALUE so that
     * all rows are returned, then filters in the DB using the per-row safety_quantity.
     * The ProductRepository already has findByMerchantIdAndQuantityLessThan which
     * approximates this; we use it with safetyQuantity=Integer.MAX_VALUE and rely
     * on a native query to do the column-vs-column comparison below.
     *
     * Implementation note: Spring Data cannot directly express col1 <= col2 via
     * derived query methods, so we delegate to the service / repository using
     * a custom JPQL query added to ProductRepository.
     */
    @GetMapping("/low-stock")
    public ResponseEntity<UserPageResponse<Product>> listLowStock(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Product> products = productRepository.findLowStockByMerchantId(
                principal.getMerchantId(), pageable);
        return ResponseEntity.ok(UserPageResponse.from(products));
    }

    /**
     * PATCH /api/user/inventory/{productId}
     * Manually set the inventory quantity for a product.
     *
     * This is a direct overwrite (not a delta). Useful for stock-take corrections.
     */
    @PatchMapping("/{productId}")
    public ResponseEntity<Product> adjustInventory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String productId,
            @RequestBody AdjustInventoryRequest req) {

        if (req.quantity() == null || req.quantity() < 0) {
            return ResponseEntity.badRequest().build();
        }

        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty() || !principal.getMerchantId().equals(productOpt.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        Product product = productOpt.get();
        int oldQty = product.getQuantity() != null ? product.getQuantity() : 0;
        product.setQuantity(req.quantity());
        Product saved = productRepository.save(product);

        log.info("Inventory adjusted for product {} (merchant {}): {} -> {}",
                productId, principal.getMerchantId(), oldQty, req.quantity());
        return ResponseEntity.ok(saved);
    }
}
