package com.shopsphere.product.service;

import com.shopsphere.common.result.PageResult;
import com.shopsphere.product.dto.ProductDetailVO;
import com.shopsphere.product.dto.ProductListQuery;
import com.shopsphere.product.dto.ProductVO;

public interface ProductService {

    /** 商品详情；不存在抛 {@code PRODUCT_NOT_FOUND}（3001）。 */
    ProductDetailVO getDetail(Long id);

    /** 列表分页，size 上限 100。 */
    PageResult<ProductVO> list(ProductListQuery query);
}
