package com.oneec.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oneec.core.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
