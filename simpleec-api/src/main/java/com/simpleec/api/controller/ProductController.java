package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Product;
import com.simpleec.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 商品 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 查詢商品列表（分頁）
     * GET /api/products?page=0&size=10
     */
    @GetMapping
    public ResponseEntity<Page<Product>> listProducts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String merchantId = principal.getMerchantId();
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productService.findByMerchantId(merchantId, pageable);

        log.info("Listed {} products for merchant {}", products.getTotalElements(), merchantId);
        return ResponseEntity.ok(products);
    }

    /**
     * 查詢單筆商品
     * GET /api/products/{productId}
     */
    @GetMapping("/{productId}")
    public ResponseEntity<Product> getProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String productId) {
        Optional<Product> product = productService.findById(productId);

        if (product.isPresent() && principal.getMerchantId().equals(product.get().getMerchantId())) {
            log.info("Retrieved product: {}", productId);
            return ResponseEntity.ok(product.get());
        } else {
            log.warn("Product not found or access denied: {}", productId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 根據 SKU 查詢商品
     * GET /api/products/sku/{sku}
     */
    @GetMapping("/sku/{sku}")
    public ResponseEntity<Product> getProductBySku(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String sku) {

        Optional<Product> product = productService.findBySku(principal.getMerchantId(), sku);

        if (product.isPresent()) {
            return ResponseEntity.ok(product.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 搜尋商品（SKU 模糊搜尋）
     * GET /api/products/search?skuPattern=PROD&page=0&size=10
     */
    @GetMapping("/search")
    public ResponseEntity<Page<Product>> searchProducts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String skuPattern,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String merchantId = principal.getMerchantId();
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productService.searchBySku(merchantId, skuPattern, pageable);

        log.info("Searched {} products with pattern '{}' for merchant {}",
            products.getTotalElements(), skuPattern, merchantId);
        return ResponseEntity.ok(products);
    }

    /**
     * 建立商品
     * POST /api/products
     */
    @PostMapping
    public ResponseEntity<Product> createProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Product product) {
        try {
            product.setMerchantId(principal.getMerchantId());
            Product created = productService.createProduct(product);
            log.info("Created product: {} (SKU: {})", created.getId(), created.getSku());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Error creating product", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 更新商品
     * PATCH /api/products/{productId}
     */
    @PatchMapping("/{productId}")
    public ResponseEntity<Product> updateProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String productId,
            @RequestBody Product productUpdate) {

        Optional<Product> existing = productService.findById(productId);

        if (existing.isEmpty() || !principal.getMerchantId().equals(existing.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        Product product = existing.get();

        if (productUpdate.getProductName() != null) {
            product.setProductName(productUpdate.getProductName());
        }
        if (productUpdate.getCostPrice() != null) {
            product.setCostPrice(productUpdate.getCostPrice());
        }
        if (productUpdate.getSuggestPrice() != null) {
            product.setSuggestPrice(productUpdate.getSuggestPrice());
        }
        if (productUpdate.getQuantity() != null) {
            product.setQuantity(productUpdate.getQuantity());
        }
        if (productUpdate.getStatus() != null) {
            product.setStatus(productUpdate.getStatus());
        }

        Product updated = productService.updateProduct(product);
        log.info("Updated product: {}", productId);
        return ResponseEntity.ok(updated);
    }

    /**
     * 更新庫存
     * PATCH /api/products/{productId}/quantity
     */
    @PatchMapping("/{productId}/quantity")
    public ResponseEntity<Void> updateQuantity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String productId,
            @RequestParam int quantity) {

        Optional<Product> existing = productService.findById(productId);
        if (existing.isEmpty() || !principal.getMerchantId().equals(existing.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }

        try {
            productService.updateQuantity(productId, quantity);
            log.info("Updated quantity for product {} to {}", productId, quantity);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error updating quantity", e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 查詢庫存不足的商品
     * GET /api/products/low-stock?page=0&size=10
     */
    @GetMapping("/low-stock")
    public ResponseEntity<Page<Product>> getLowStockProducts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String merchantId = principal.getMerchantId();
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productService.findLowStock(merchantId, pageable);

        log.info("Found {} products with low stock for merchant {}",
            products.getTotalElements(), merchantId);
        return ResponseEntity.ok(products);
    }
}
