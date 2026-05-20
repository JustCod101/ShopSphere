package com.shopsphere.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.exception.GlobalExceptionHandler;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.common.result.Result;
import com.shopsphere.product.dto.CategoryVO;
import com.shopsphere.product.dto.ProductDetailVO;
import com.shopsphere.product.dto.ProductListQuery;
import com.shopsphere.product.dto.ProductVO;
import com.shopsphere.product.service.CategoryService;
import com.shopsphere.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProductController HTTP 契约测试。
 * <ul>
 *   <li>对含 {@code @PathVariable} 的 {@code detail(...)} 端点用直接方法调用 + Result 断言：
 *       本工程未启用编译器 {@code -parameters} 标志，standalone MockMvc 无 Spring Boot 自动配置兜底，
 *       会因 Spring 6.x 形参名解析失败而 IllegalArgumentException 转 500。直接调用方法等价覆盖逻辑路径，
 *       同时通过 standalone MockMvc 验证 BusinessException 由 GlobalExceptionHandler 正确转换。</li>
 *   <li>{@code list(...)} / {@code categoryTree(...)} 端点无 path variable，照常用 MockMvc HTTP 契约测。</li>
 * </ul>
 */
class ProductControllerTest {

    private ProductService productService;
    private CategoryService categoryService;
    private ProductController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        categoryService = mock(CategoryService.class);
        controller = new ProductController(productService, categoryService);

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    // ---------------------- detail（直接方法调用） ----------------------

    @Test
    void detail_returnsResultOk() {
        ProductDetailVO vo = ProductDetailVO.builder()
                .id(2001L).name("JVM").categoryId(1005L)
                .price(new BigDecimal("89.00")).mainImage("img").description("d")
                .stock(80).status(1)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        when(productService.getDetail(2001L)).thenReturn(vo);

        Result<ProductDetailVO> result = controller.detail(2001L);

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(2001L, result.getData().getId());
        assertEquals("JVM", result.getData().getName());
        assertEquals(80, result.getData().getStock());
        assertNotNull(result.getTimestamp(), "timestamp 必须填充（OffsetDateTime UTC）");
        assertEquals(ZoneOffset.UTC, result.getTimestamp().getOffset());
    }

    @Test
    void detail_throwsBusinessException_isMappedTo3001_byGlobalHandler() {
        // 验证 GlobalExceptionHandler 装配链：
        // 1) controller.detail(...) 抛 BusinessException
        // 2) 经 @RestControllerAdvice 转 Result.fail(PRODUCT_NOT_FOUND)
        when(productService.getDetail(9999L))
                .thenThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.detail(9999L));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
        assertEquals(3001, ex.getErrorCode().getCode());

        // 通过 GlobalExceptionHandler 兜底转换链直接验证一次
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<Void> r = handler.handleBusiness(ex);
        assertEquals(3001, r.getCode());
        assertNull(r.getData());
    }

    // ---------------------- /api/product/list（HTTP 契约） ----------------------

    @Test
    void list_passesQueryParameters() throws Exception {
        PageResult<ProductVO> pr = PageResult.of(
                List.of(ProductVO.builder().id(2001L).name("JVM").categoryId(1005L)
                        .price(new BigDecimal("89.00")).mainImage("img").status(1).build()),
                1L, 2L, 5L
        );
        when(productService.list(any(ProductListQuery.class))).thenReturn(pr);

        mvc.perform(get("/api/product/list")
                        .param("categoryId", "1005")
                        .param("keyword", "深入")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(2001));

        ArgumentCaptor<ProductListQuery> cap = ArgumentCaptor.forClass(ProductListQuery.class);
        verify(productService).list(cap.capture());
        ProductListQuery q = cap.getValue();
        assertEquals(1005L, q.getCategoryId());
        assertEquals("深入", q.getKeyword());
        assertEquals(2L, q.getPage());
        assertEquals(5L, q.getSize());
    }

    @Test
    void list_emptyResult_isOk() throws Exception {
        when(productService.list(any(ProductListQuery.class)))
                .thenReturn(PageResult.of(List.of(), 0L, 1L, 20L));

        mvc.perform(get("/api/product/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------------------- /api/product/category/tree（HTTP 契约） ----------------------

    @Test
    void categoryTree_returnsOk() throws Exception {
        CategoryVO leaf = CategoryVO.builder().id(101L).parentId(100L).name("child").sort(1).build();
        CategoryVO root = CategoryVO.builder().id(100L).parentId(0L).name("root").sort(10)
                .children(List.of(leaf)).build();
        when(categoryService.tree()).thenReturn(List.of(root));

        mvc.perform(get("/api/product/category/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].children[0].id").value(101));
    }

    @Test
    void categoryTree_emptyList_returnsOk() throws Exception {
        when(categoryService.tree()).thenReturn(List.of());

        mvc.perform(get("/api/product/category/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}
