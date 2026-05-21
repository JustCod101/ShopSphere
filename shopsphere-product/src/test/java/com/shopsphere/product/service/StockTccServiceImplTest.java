package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.shopsphere.api.product.dto.StockItem;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.product.entity.StockTccLogEntity;
import com.shopsphere.product.mapper.StockTccLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockTccServiceImpl 骨架单测（Mockito，mock StockTccLogMapper + StockRedisService）。
 * 覆盖 try/confirm/cancel 的幂等表写入 + StockRedisService 调用。
 */
class StockTccServiceImplTest {

    private StockTccLogMapper tccLogMapper;
    private StockRedisService stockRedisService;
    private StockTccServiceImpl service;

    @BeforeEach
    void setUp() {
        tccLogMapper = mock(StockTccLogMapper.class);
        stockRedisService = mock(StockRedisService.class);
        service = new StockTccServiceImpl(tccLogMapper, stockRedisService);
    }

    @SuppressWarnings("unchecked")
    private void stubExists(boolean exists) {
        when(tccLogMapper.exists(any(Wrapper.class))).thenReturn(exists);
    }

    private static StockTccLogEntity tryRow(long productId, int qty) {
        return StockTccLogEntity.builder()
                .orderId(9001L).productId(productId).phase("TRY").state(1).quantity(qty)
                .build();
    }

    // ---------------------- tryStock ----------------------

    @Test
    void tryStock_success_preDeductsAndWritesLog() {
        stubExists(false);
        when(stockRedisService.preDeduct(2001L, 3)).thenReturn(97L);
        StockTccDTO dto = new StockTccDTO("x1", 9001L, List.of(new StockItem(2001L, 3)));

        service.tryStock(dto);

        verify(stockRedisService).preDeduct(2001L, 3);
        ArgumentCaptor<StockTccLogEntity> cap = ArgumentCaptor.forClass(StockTccLogEntity.class);
        verify(tccLogMapper).insert(cap.capture());
        assertEquals("TRY", cap.getValue().getPhase());
        assertEquals(3, cap.getValue().getQuantity());
        assertEquals(1, cap.getValue().getState());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryStock_idempotent_skipsAlreadyLoggedItem() {
        stubExists(true);   // (order,product,TRY) 已存在
        StockTccDTO dto = new StockTccDTO("x1", 9001L, List.of(new StockItem(2001L, 3)));

        service.tryStock(dto);

        verify(stockRedisService, never()).preDeduct(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(tccLogMapper, never()).insert(any(StockTccLogEntity.class));
    }

    @Test
    void tryStock_insufficientStock_throwsStockNotEnough() {
        stubExists(false);
        when(stockRedisService.preDeduct(2001L, 999999))
                .thenReturn(StockRedisService.RESULT_INSUFFICIENT);
        StockTccDTO dto = new StockTccDTO("x1", 9001L, List.of(new StockItem(2001L, 999999)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.tryStock(dto));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, ex.getErrorCode());
        verify(tccLogMapper, never()).insert(any(StockTccLogEntity.class));
    }

    @Test
    void tryStock_keyMissing_throwsStockPredeductFail() {
        stubExists(false);
        when(stockRedisService.preDeduct(2001L, 3))
                .thenReturn(StockRedisService.RESULT_KEY_MISSING);
        StockTccDTO dto = new StockTccDTO("x1", 9001L, List.of(new StockItem(2001L, 3)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.tryStock(dto));
        assertEquals(ErrorCode.STOCK_PREDEDUCT_FAIL, ex.getErrorCode());
    }

    @Test
    void tryStock_emptyItems_noop() {
        service.tryStock(new StockTccDTO("x1", 9001L, List.of()));
        verify(tccLogMapper, never()).insert(any(StockTccLogEntity.class));
    }

    // ---------------------- confirmStock ----------------------

    @Test
    @SuppressWarnings("unchecked")
    void confirmStock_writesConfirmLogForEachTryRow() {
        when(tccLogMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tryRow(2001L, 3), tryRow(2002L, 5)));
        when(tccLogMapper.exists(any(Wrapper.class))).thenReturn(false);   // 无 CONFIRM 记录

        service.confirmStock(new StockTccActionDTO("x1", 9001L));

        ArgumentCaptor<StockTccLogEntity> cap = ArgumentCaptor.forClass(StockTccLogEntity.class);
        verify(tccLogMapper, times(2)).insert(cap.capture());
        assertEquals("CONFIRM", cap.getAllValues().get(0).getPhase());
        assertEquals(3, cap.getAllValues().get(0).getQuantity());
        // Confirm 无 Redis 动作
        verify(stockRedisService, never()).restore(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmStock_idempotent_skipsLoggedConfirm() {
        when(tccLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(tryRow(2001L, 3)));
        when(tccLogMapper.exists(any(Wrapper.class))).thenReturn(true);    // CONFIRM 已存在

        service.confirmStock(new StockTccActionDTO("x1", 9001L));

        verify(tccLogMapper, never()).insert(any(StockTccLogEntity.class));
    }

    // ---------------------- cancelStock ----------------------

    @Test
    @SuppressWarnings("unchecked")
    void cancelStock_restoresAndWritesCancelLogForEachTryRow() {
        when(tccLogMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tryRow(2001L, 3), tryRow(2002L, 5)));
        when(tccLogMapper.exists(any(Wrapper.class))).thenReturn(false);

        service.cancelStock(new StockTccActionDTO("x2", 9001L));

        verify(stockRedisService).restore(2001L, 3);
        verify(stockRedisService).restore(2002L, 5);
        ArgumentCaptor<StockTccLogEntity> cap = ArgumentCaptor.forClass(StockTccLogEntity.class);
        verify(tccLogMapper, times(2)).insert(cap.capture());
        assertEquals("CANCEL", cap.getAllValues().get(0).getPhase());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelStock_idempotent_skipsLoggedCancel() {
        when(tccLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(tryRow(2001L, 3)));
        when(tccLogMapper.exists(any(Wrapper.class))).thenReturn(true);    // CANCEL 已存在

        service.cancelStock(new StockTccActionDTO("x2", 9001L));

        verify(stockRedisService, never()).restore(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(tccLogMapper, never()).insert(any(StockTccLogEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelStock_noTryRows_noop() {
        // 空回滚（Cancel 先于 Try）：骨架静默不动作，完整 state=0 标记留 T3.3
        when(tccLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cancelStock(new StockTccActionDTO("x2", 9999L));

        verify(stockRedisService, never()).restore(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(tccLogMapper, never()).insert(any(StockTccLogEntity.class));
    }
}
