package com.simpleec.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.entity.Product;
import com.simpleec.core.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;

    public PageResult<Product> list(Long merchantId, int page, int size) {
        Page<Product> result = productMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getMerchantId, merchantId)
                        .ne(Product::getStatus, "deleted")
                        .orderByDesc(Product::getCreatedAt)
        );
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    public Product getById(Long id) {
        return productMapper.selectById(id);
    }

    public void save(Product product) {
        if (product.getId() == null) {
            productMapper.insert(product);
        } else {
            productMapper.updateById(product);
        }
    }

    public Product getByItemNumber(Long merchantId, String itemNumber) {
        return productMapper.selectOne(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getMerchantId, merchantId)
                        .eq(Product::getItemNumber, itemNumber)
        );
    }
}
