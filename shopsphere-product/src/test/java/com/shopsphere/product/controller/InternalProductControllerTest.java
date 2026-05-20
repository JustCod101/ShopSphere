package com.shopsphere.product.controller;

import com.shopsphere.api.product.dto.StockTccCmd;
import com.shopsphere.api.product.dto.StockTccConfirmCmd;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * InternalProductController（T2.1 骨架）：三段方法体显式 throw SERVER_ERROR(1500)，
 * 明确"未实现"状态，T2.4 落地真实 TCC 实现。
 */
class InternalProductControllerTest {

    private final InternalProductController controller = new InternalProductController();

    @Test
    void tryStock_throws1500_withNotImplementedMessage() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.tryStock(new StockTccCmd()));
        assertEquals(ErrorCode.SERVER_ERROR, ex.getErrorCode());
        assertEquals(1500, ex.getErrorCode().getCode());
        assertEquals("stock/try not implemented (T2.4)", ex.getMessage());
    }

    @Test
    void confirmStock_throws1500_withNotImplementedMessage() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.confirmStock(new StockTccConfirmCmd()));
        assertEquals(ErrorCode.SERVER_ERROR, ex.getErrorCode());
        assertEquals("stock/confirm not implemented (T2.4)", ex.getMessage());
    }

    @Test
    void cancelStock_throws1500_withNotImplementedMessage() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.cancelStock(new StockTccConfirmCmd()));
        assertEquals(ErrorCode.SERVER_ERROR, ex.getErrorCode());
        assertEquals("stock/cancel not implemented (T2.4)", ex.getMessage());
    }
}
