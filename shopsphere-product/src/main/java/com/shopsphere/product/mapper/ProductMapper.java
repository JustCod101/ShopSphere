package com.shopsphere.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.product.entity.ProductEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
}
