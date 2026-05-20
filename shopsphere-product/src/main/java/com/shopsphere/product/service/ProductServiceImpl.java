package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
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

    @Override
    public ProductDetailVO getDetail(Long id) {
        ProductEntity p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        ProductStockEntity s = stockMapper.selectById(id);
        int sellable;
        if (s == null) {
            sellable = 0;
        } else {
            int diff = s.getStock() - s.getLockedStock();
            if (diff < 0) {
                // locked > stock 属数据异常；不抑制告警，仅 0 兜底避免负库存外泄
                log.warn("negative sellable stock for productId={}, stock={}, locked={}",
                        id, s.getStock(), s.getLockedStock());
                sellable = 0;
            } else {
                sellable = diff;
            }
        }
        return ProductDetailVO.from(p, sellable);
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
