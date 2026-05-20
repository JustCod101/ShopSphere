package com.shopsphere.product.service;

import com.shopsphere.product.dto.CategoryVO;
import com.shopsphere.product.entity.CategoryEntity;
import com.shopsphere.product.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类目树装配：一次性 selectList，按 parent_id 在内存分组成树。
 * <p>MP 全局逻辑删除自动按 status=1 过滤，业务无需主动加 where 条件。
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /** 根类目占位 parent_id（与 DB 默认值一致） */
    private static final Long ROOT_PARENT_ID = 0L;

    private final CategoryMapper categoryMapper;

    @Override
    public List<CategoryVO> tree() {
        List<CategoryEntity> all = categoryMapper.selectList(null);
        Map<Long, List<CategoryVO>> byParent = new HashMap<>();
        for (CategoryEntity c : all) {
            byParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>())
                    .add(CategoryVO.from(c));
        }
        List<CategoryVO> roots = byParent.getOrDefault(ROOT_PARENT_ID, new ArrayList<>());
        roots.sort(Comparator.comparingInt(CategoryVO::getSort));
        attachChildren(roots, byParent);
        return roots;
    }

    private void attachChildren(List<CategoryVO> nodes, Map<Long, List<CategoryVO>> byParent) {
        for (CategoryVO n : nodes) {
            List<CategoryVO> children = byParent.getOrDefault(n.getId(), new ArrayList<>());
            children.sort(Comparator.comparingInt(CategoryVO::getSort));
            n.setChildren(children);
            attachChildren(children, byParent);
        }
    }
}
