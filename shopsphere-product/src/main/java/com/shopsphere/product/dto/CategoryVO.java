package com.shopsphere.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shopsphere.product.entity.CategoryEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 类目树节点 VO（契约扩展 GET /api/product/category/tree）。
 * <p>children 为 {@code null} 或空数组语义等同（叶子节点）；序列化时空数组也保留。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Long parentId;
    private Integer sort;

    private List<CategoryVO> children;

    public static CategoryVO from(CategoryEntity e) {
        return CategoryVO.builder()
                .id(e.getId())
                .name(e.getName())
                .parentId(e.getParentId())
                .sort(e.getSort())
                .build();
    }
}
