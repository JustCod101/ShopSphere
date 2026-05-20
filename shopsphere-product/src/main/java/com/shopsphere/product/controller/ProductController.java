package com.shopsphere.product.controller;

import com.shopsphere.common.context.PublicApi;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.common.result.Result;
import com.shopsphere.product.dto.CategoryVO;
import com.shopsphere.product.dto.ProductDetailVO;
import com.shopsphere.product.dto.ProductListQuery;
import com.shopsphere.product.dto.ProductVO;
import com.shopsphere.product.service.CategoryService;
import com.shopsphere.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外商品接口（契约 §6.2 GET 部分）。
 * <ul>
 *   <li>{@code GET /api/product/{id}} — 详情，stock = 可售量</li>
 *   <li>{@code GET /api/product/list} — 列表分页，size 上限 100</li>
 *   <li>{@code GET /api/product/category/tree} — 类目树（本期挂在 product 前缀下，零 Gateway 改动）</li>
 * </ul>
 * <p>全部 {@link PublicApi}：契约 §3.1 白名单已含 {@code /api/product/**}，登录态可选。
 */
@RestController
@RequestMapping("/api/product")
@PublicApi
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final CategoryService categoryService;

    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable("id") Long id) {
        return Result.ok(productService.getDetail(id));
    }

    @GetMapping("/list")
    public Result<PageResult<ProductVO>> list(ProductListQuery query) {
        return Result.ok(productService.list(query));
    }

    @GetMapping("/category/tree")
    public Result<List<CategoryVO>> categoryTree() {
        return Result.ok(categoryService.tree());
    }
}
