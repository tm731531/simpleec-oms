package com.simpleec.api.controller;

import com.simpleec.core.entity.Product;
import com.simpleec.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * GET /api/products?merchantId=M001&page=0&size=10
     */
    @GetMapping
    public ResponseEntity<Page<Product>> listProducts(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

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
    public ResponseEntity<Product> getProduct(@PathVariable String productId) {
        Optional<Product> product = productService.findById(productId);

        if (product.isPresent()) {
            log.info("Retrieved product: {}", productId);
            return ResponseEntity.ok(product.get());
        } else {
            log.warn("Product not found: {}", productId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 根據 SKU 查詢商品
     * GET /api/products/sku/{sku}?merchantId=M001
     */
    @GetMapping("/sku/{sku}")
    public ResponseEntity<Product> getProductBySku(
            @PathVariable String sku,
            @RequestParam String merchantId) {

        Optional<Product> product = productService.findBySku(merchantId, sku);

        if (product.isPresent()) {
            return ResponseEntity.ok(product.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 搜尋商品（SKU 模糊搜尋）
     * GET /api/products/search?merchantId=M001&skuPattern=PROD&page=0&size=10
     */
    @GetMapping("/search")
    public ResponseEntity<Page<Product>> searchProducts(
            @RequestParam String merchantId,
            @RequestParam String skuPattern,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

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
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        try {
            Product created = productService.createProduct(product);
            log.info("Created product: {} (SKU: {})", created.getProductId(), created.getSku());
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
            @PathVariable String productId,
            @RequestBody Product productUpdate) {

        Optional<Product> existing = productService.findById(productId);

        if (existing.isPresent()) {
            Product product = existing.get();

            // 更新允許修改的欄位
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
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 更新庫存
     * PATCH /api/products/{productId}/quantity
     */
    @PatchMapping("/{productId}/quantity")
    public ResponseEntity<Void> updateQuantity(
            @PathVariable String productId,
            @RequestParam int quantity) {

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
     * GET /api/products/low-stock?merchantId=M001&page=0&size=10
     */
    @GetMapping("/low-stock")
    public ResponseEntity<Page<Product>> getLowStockProducts(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productService.findLowStock(merchantId, pageable);

        log.info("Found {} products with low stock for merchant {}",
            products.getTotalElements(), merchantId);
        return ResponseEntity.ok(products);
    }
}
