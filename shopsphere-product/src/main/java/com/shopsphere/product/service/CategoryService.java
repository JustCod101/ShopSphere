package com.shopsphere.product.service;

import com.shopsphere.product.dto.CategoryVO;

import java.util.List;

public interface CategoryService {

    /** 完整类目树：根节点 parent_id=0，按 sort 升序；自动过滤逻辑删除态。 */
    List<CategoryVO> tree();
}
