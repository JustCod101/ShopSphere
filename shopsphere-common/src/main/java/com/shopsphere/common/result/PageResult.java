package com.shopsphere.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 列表分页结构，对齐 docs/api-contracts.md §1.2（与 MyBatis-Plus IPage 对齐）。
 * <p>请求分页参数统一 {@code ?page=1&size=20}（page 从 1 起，size 上限 100 由各服务截断）。
 *
 * @param <T> 记录类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<T> records;
    private long total;
    private long page;
    private long size;

    /**
     * 从 MyBatis-Plus 分页对象适配（mybatis-plus-annotation 为 provided 依赖，
     * 仅在引入方有 MyBatis-Plus 运行时时调用）。
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(),
                page.getCurrent(), page.getSize());
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(records, total, page, size);
    }
}
