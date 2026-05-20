package com.shopsphere.product.controller;

import com.shopsphere.common.context.PublicApi;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台管理 CRUD 占位（契约外，运营后台用）。
 *
 * <p><b>T2.1 本期仅骨架</b>：方法体 throw 1500，明确未实现。本期不接入 Gateway，
 * 仅作类/路径签名预留，便于后续运营平台对齐。
 *
 * <p>实际后台权限模型 / 审批流是 Phase 5 运维治理事项。
 */
@RestController
@RequestMapping("/internal/admin")
@PublicApi
@RequiredArgsConstructor
public class InternalAdminController {

    @PostMapping("/product")
    public Result<Long> createProduct(@RequestBody Object body) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "admin createProduct not implemented");
    }

    @PutMapping("/product/{id}")
    public Result<Void> updateProduct(@PathVariable("id") Long id, @RequestBody Object body) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "admin updateProduct not implemented");
    }

    @DeleteMapping("/product/{id}")
    public Result<Void> deleteProduct(@PathVariable("id") Long id) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "admin deleteProduct not implemented");
    }

    @PostMapping("/category")
    public Result<Long> createCategory(@RequestBody Object body) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "admin createCategory not implemented");
    }

    @PutMapping("/category/{id}")
    public Result<Void> updateCategory(@PathVariable("id") Long id, @RequestBody Object body) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "admin updateCategory not implemented");
    }

    @DeleteMapping("/category/{id}")
    public Result<Void> deleteCategory(@PathVariable("id") Long id) {
        throw new BusinessException(ErrorCode.SERVER_ERROR, "admin deleteCategory not implemented");
    }
}
