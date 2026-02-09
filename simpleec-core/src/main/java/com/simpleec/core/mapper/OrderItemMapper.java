package com.simpleec.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simpleec.core.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
