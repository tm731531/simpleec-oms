package com.oneec.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oneec.core.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
