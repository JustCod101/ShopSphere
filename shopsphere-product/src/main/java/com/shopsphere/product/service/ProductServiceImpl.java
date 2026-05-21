package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.product.dto.ProductDetailVO;
import com.shopsphere.product.dto.ProductListQuery;
import com.shopsphere.product.dto.ProductVO;
import com.shopsphere.product.entity.ProductEntity;
import com.shopsphere.product.entity.ProductStockEntity;
import com.shopsphere.product.mapper.ProductMapper;
import com.shopsphere.product.mapper.ProductStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 100L;

    private final ProductMapper productMapper;
    private final ProductStockMapper stockMapper;
    private final ProductCacheService cacheService;

    @Override
    public ProductDetailVO getDetail(Long id) {
        // T2.2：详情走 Cache-Aside；缓存 miss 时由 cacheService 回调 loadDetailFromDb
        return cacheService.getOrLoadDetail(id, this::loadDetailFromDb);
    }

    /**
     * DB 加载详情。返回 {@code null} 表示商品不存在 —— 由 {@link ProductCacheService}
     * 转为空值标记缓存 + 抛 {@code PRODUCT_NOT_FOUND}（防穿透）。
     */
    private ProductDetailVO loadDetailFromDb(Long id) {
        ProductEntity p = productMapper.selectById(id);
        if (p == null) {
            return null;
        }
        ProductStockEntity s = stockMapper.selectById(id);
        return ProductDetailVO.from(p, computeSellable(id, s));
    }

    /** 可售量 = stock - locked_stock；负值属数据异常，告警并 0 兜底。 */
    private int computeSellable(Long id, ProductStockEntity s) {
        if (s == null) {
            return 0;
        }
        int diff = s.getStock() - s.getLockedStock();
        if (diff < 0) {
            log.warn("negative sellable stock for productId={}, stock={}, locked={}",
                    id, s.getStock(), s.getLockedStock());
            return 0;
        }
        return diff;
    }

    @Override
    public PageResult<ProductVO> list(ProductListQuery query) {
        long page = query.getPage() == null ? DEFAULT_PAGE : Math.max(1L, query.getPage());
        long size = query.getSize() == null ? DEFAULT_SIZE
                : Math.min(MAX_SIZE, Math.max(1L, query.getSize()));

        LambdaQueryWrapper<ProductEntity> w = new LambdaQueryWrapper<>();
        if (query.getCategoryId() != null) {
            w.eq(ProductEntity::getCategoryId, query.getCategoryId());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            w.like(ProductEntity::getName, query.getKeyword());
        }
        w.orderByDesc(ProductEntity::getId);

        IPage<ProductEntity> mpPage = productMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(mpPage.convert(ProductVO::from));
    }
}
