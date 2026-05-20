package com.shopsphere.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.product.entity.ProductStockEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStockEntity> {
}
