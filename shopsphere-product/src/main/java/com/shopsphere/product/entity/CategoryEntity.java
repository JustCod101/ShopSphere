package com.shopsphere.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * t_category 实体。无时间列；status 由 MP 全局逻辑删除接管（1=有效 / 0=已删除）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_category")
public class CategoryEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 0 = 根类目 */
    private Long parentId;

    private Integer sort;

    /** 1=有效 0=逻辑删除（MP 全局拦截，业务代码无需主动过滤） */
    private Integer status;
}
