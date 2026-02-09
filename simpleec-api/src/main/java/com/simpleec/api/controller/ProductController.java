package com.simpleec.api.controller;

import com.simpleec.common.model.ApiResponse;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.entity.Product;
import com.simpleec.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<PageResult<Product>> list(
            @RequestParam Long merchantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(productService.list(merchantId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> get(@PathVariable Long id) {
        return ApiResponse.ok(productService.getById(id));
    }

    @PostMapping
    public ApiResponse<Product> create(@RequestBody Product product) {
        productService.save(product);
        return ApiResponse.ok(product);
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        productService.save(product);
        return ApiResponse.ok(product);
    }
}
