package com.shopsphere.product.service;

import com.shopsphere.product.dto.CategoryVO;
import com.shopsphere.product.entity.CategoryEntity;
import com.shopsphere.product.mapper.CategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CategoryServiceImpl 单测：内存装树 + 排序 + 空场景。
 */
class CategoryServiceImplTest {

    private CategoryMapper categoryMapper;
    private CategoryServiceImpl service;

    @BeforeEach
    void setUp() {
        categoryMapper = mock(CategoryMapper.class);
        service = new CategoryServiceImpl(categoryMapper);
    }

    private static CategoryEntity cat(long id, long parentId, String name, int sort) {
        return CategoryEntity.builder()
                .id(id).parentId(parentId).name(name).sort(sort).status(1).build();
    }

    @Test
    void tree_assemblesRootsSortedByOrderField() {
        // 5 个根类目，故意打乱顺序传入；期望按 sort 升序输出
        when(categoryMapper.selectList(isNull())).thenReturn(List.of(
                cat(3L, 0L, "C", 30),
                cat(1L, 0L, "A", 10),
                cat(5L, 0L, "E", 50),
                cat(2L, 0L, "B", 20),
                cat(4L, 0L, "D", 40)
        ));

        List<CategoryVO> tree = service.tree();

        assertEquals(5, tree.size());
        assertEquals(10, tree.get(0).getSort());
        assertEquals(20, tree.get(1).getSort());
        assertEquals(30, tree.get(2).getSort());
        assertEquals(40, tree.get(3).getSort());
        assertEquals(50, tree.get(4).getSort());
        // 叶子节点 children 应为空 list（非 null）
        for (CategoryVO n : tree) {
            assertNotNull(n.getChildren(), "children 必须为空 list 而非 null");
            assertTrue(n.getChildren().isEmpty());
        }
    }

    @Test
    void tree_attachesChildrenRecursively() {
        when(categoryMapper.selectList(isNull())).thenReturn(List.of(
                cat(100L, 0L, "root", 10),
                cat(101L, 100L, "child-A", 2),
                cat(102L, 100L, "child-B", 1)
        ));

        List<CategoryVO> tree = service.tree();

        assertEquals(1, tree.size());
        CategoryVO root = tree.get(0);
        assertEquals(100L, root.getId());
        assertNotNull(root.getChildren());
        assertEquals(2, root.getChildren().size());
        // 按 sort 升序：sort=1 (id=102) 在前，sort=2 (id=101) 在后
        assertEquals(102L, root.getChildren().get(0).getId());
        assertEquals(101L, root.getChildren().get(1).getId());
        // 叶子的 children 应为空 list
        assertNotNull(root.getChildren().get(0).getChildren());
        assertTrue(root.getChildren().get(0).getChildren().isEmpty());
        assertTrue(root.getChildren().get(1).getChildren().isEmpty());
    }

    @Test
    void tree_returnsEmptyWhenNoRoots() {
        when(categoryMapper.selectList(isNull())).thenReturn(List.of());

        List<CategoryVO> tree = service.tree();
        assertNotNull(tree);
        assertTrue(tree.isEmpty());
    }

    @Test
    void tree_returnsEmptyWhenOnlyOrphans() {
        // 全是非根节点（parent_id 不为 0 且无对应根）
        when(categoryMapper.selectList(isNull())).thenReturn(List.of(
                cat(201L, 999L, "orphan-A", 1),
                cat(202L, 999L, "orphan-B", 2)
        ));

        List<CategoryVO> tree = service.tree();
        assertNotNull(tree);
        assertTrue(tree.isEmpty(), "无 parent_id=0 的节点 → 空树");
    }
}
