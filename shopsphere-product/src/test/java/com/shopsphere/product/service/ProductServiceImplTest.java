package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductServiceImpl 单测（纯 Mockito，不起 Spring）。
 *
 * <p>T2.2 后 {@code getDetail} 委托 {@link ProductCacheService}；本测用 stub 让
 * {@code getOrLoadDetail} 忠实回放缓存契约（调 dbLoader → null 转 3001），从而间接验证
 * 私有方法 {@code loadDetailFromDb} / {@code computeSellable} 的 DB 加载逻辑。
 * 真正的缓存分支（防穿透/击穿/雪崩/双删）由 {@code ProductCacheServiceTest} 覆盖。
 */
class ProductServiceImplTest {

    private ProductMapper productMapper;
    private ProductStockMapper stockMapper;
    private ProductCacheService cacheService;
    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        stockMapper = mock(ProductStockMapper.class);
        cacheService = mock(ProductCacheService.class);
        service = new ProductServiceImpl(productMapper, stockMapper, cacheService);
    }

    /** stub：getOrLoadDetail 回放缓存契约 —— 调 dbLoader，null → PRODUCT_NOT_FOUND。 */
    @SuppressWarnings("unchecked")
    private void stubCachePassthrough() {
        when(cacheService.getOrLoadDetail(anyLong(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Function<Long, ProductDetailVO> loader = inv.getArgument(1);
            ProductDetailVO vo = loader.apply(id);
            if (vo == null) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            return vo;
        });
    }

    private static ProductEntity buildEntity() {
        return ProductEntity.builder()
                .id(2001L)
                .name("深入理解 Java 虚拟机")
                .categoryId(1005L)
                .price(new BigDecimal("89.00"))
                .mainImage("https://example.com/jvm.jpg")
                .description("desc")
                .status(1)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    // ---------------------- getDetail（经缓存层委托，验证 DB 加载逻辑） ----------------------

    @Test
    void getDetail_returnsDetailWithSellableStock() {
        stubCachePassthrough();
        ProductEntity p = buildEntity();
        when(productMapper.selectById(2001L)).thenReturn(p);
        when(stockMapper.selectById(2001L))
                .thenReturn(ProductStockEntity.builder()
                        .productId(2001L).stock(100).lockedStock(20).version(0).build());

        ProductDetailVO vo = service.getDetail(2001L);

        assertEquals(2001L, vo.getId());
        assertEquals("深入理解 Java 虚拟机", vo.getName());
        assertEquals(80, vo.getStock(), "sellable = stock - locked = 100 - 20 = 80");
        assertEquals(1, vo.getStatus());
        assertNotNull(vo.getCreatedAt());
    }

    @Test
    void getDetail_throwsWhenProductMissing() {
        stubCachePassthrough();
        when(productMapper.selectById(9999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getDetail(9999L));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
        assertEquals(3001, ex.getErrorCode().getCode());
        // 商品不存在时 loadDetailFromDb 前置短路，不查 stock 表
        verify(stockMapper, org.mockito.Mockito.never()).selectById(any());
    }

    @Test
    void getDetail_returnsZeroWhenStockRowMissing() {
        stubCachePassthrough();
        when(productMapper.selectById(2001L)).thenReturn(buildEntity());
        when(stockMapper.selectById(2001L)).thenReturn(null);

        ProductDetailVO vo = service.getDetail(2001L);
        assertEquals(0, vo.getStock(), "stock 行缺失 → sellable=0，不抛异常");
        assertEquals(2001L, vo.getId());
    }

    @Test
    void getDetail_clampsNegativeSellableToZero() {
        stubCachePassthrough();
        when(productMapper.selectById(2001L)).thenReturn(buildEntity());
        when(stockMapper.selectById(2001L))
                .thenReturn(ProductStockEntity.builder()
                        .productId(2001L).stock(5).lockedStock(10).version(0).build());

        ProductDetailVO vo = service.getDetail(2001L);
        assertEquals(0, vo.getStock(),
                "locked > stock 属数据异常，sellable 应兜底为 0，不得返回负数");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDetail_delegatesToCacheService() {
        // 验证确实经过缓存层（而非直连 DB）
        ProductDetailVO cached = ProductDetailVO.builder().id(2001L).stock(7).build();
        when(cacheService.getOrLoadDetail(anyLong(), any())).thenReturn(cached);

        ProductDetailVO vo = service.getDetail(2001L);

        assertEquals(7, vo.getStock());
        verify(cacheService).getOrLoadDetail(org.mockito.ArgumentMatchers.eq(2001L), any());
        // 委托缓存层时不应直接碰 mapper
        verify(productMapper, org.mockito.Mockito.never()).selectById(any());
    }

    // ---------------------- list ----------------------

    @SuppressWarnings("unchecked")
    private void mockPageReturn() {
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(inv -> {
                    Page<ProductEntity> p = inv.getArgument(0);
                    p.setRecords(List.of(buildEntity()));
                    p.setTotal(1L);
                    return p;
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_appliesDefaultsWhenNullPageSize() {
        mockPageReturn();
        ProductListQuery q = new ProductListQuery();

        PageResult<ProductVO> res = service.list(q);

        ArgumentCaptor<Page> pageCap = ArgumentCaptor.forClass(Page.class);
        verify(productMapper).selectPage(pageCap.capture(), any(Wrapper.class));
        assertEquals(1L, pageCap.getValue().getCurrent(), "page 默认 1");
        assertEquals(20L, pageCap.getValue().getSize(), "size 默认 20");
        assertEquals(1L, res.getTotal());
        assertEquals(1, res.getRecords().size());
        assertEquals(2001L, res.getRecords().get(0).getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_clampsSizeAbove100() {
        mockPageReturn();
        ProductListQuery q = new ProductListQuery();
        q.setPage(1L);
        q.setSize(999L);

        service.list(q);

        ArgumentCaptor<Page> pageCap = ArgumentCaptor.forClass(Page.class);
        verify(productMapper).selectPage(pageCap.capture(), any(Wrapper.class));
        assertEquals(1L, pageCap.getValue().getCurrent());
        assertEquals(100L, pageCap.getValue().getSize(), "size > 100 被截断到 100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_clampsPageBelow1() {
        mockPageReturn();
        ProductListQuery q = new ProductListQuery();
        q.setPage(-1L);
        q.setSize(10L);

        service.list(q);

        ArgumentCaptor<Page> pageCap = ArgumentCaptor.forClass(Page.class);
        verify(productMapper).selectPage(pageCap.capture(), any(Wrapper.class));
        assertEquals(1L, pageCap.getValue().getCurrent(), "page < 1 被抬升到 1");
        assertEquals(10L, pageCap.getValue().getSize());
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_clampsSizeBelow1() {
        mockPageReturn();
        ProductListQuery q = new ProductListQuery();
        q.setPage(2L);
        q.setSize(0L);

        service.list(q);

        ArgumentCaptor<Page> pageCap = ArgumentCaptor.forClass(Page.class);
        verify(productMapper).selectPage(pageCap.capture(), any(Wrapper.class));
        assertEquals(2L, pageCap.getValue().getCurrent());
        assertEquals(1L, pageCap.getValue().getSize(), "size < 1 被抬升到 1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_buildsWrapperWithCategoryAndKeyword() {
        mockPageReturn();
        ProductListQuery q = new ProductListQuery();
        q.setCategoryId(1005L);
        q.setKeyword("深入");
        q.setPage(2L);
        q.setSize(5L);

        service.list(q);

        ArgumentCaptor<Page> pageCap = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper> wrapperCap = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectPage(pageCap.capture(), wrapperCap.capture());
        assertEquals(2L, pageCap.getValue().getCurrent());
        assertEquals(5L, pageCap.getValue().getSize());
        assertNotNull(wrapperCap.getValue(), "wrapper 不应为 null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_emptyKeyword_isIgnored() {
        mockPageReturn();
        ProductListQuery q = new ProductListQuery();
        q.setKeyword("   ");
        q.setPage(1L);
        q.setSize(20L);

        PageResult<ProductVO> res = service.list(q);
        assertEquals(1L, res.getTotal());
        verify(productMapper).selectPage(any(Page.class), any(Wrapper.class));
    }
}
