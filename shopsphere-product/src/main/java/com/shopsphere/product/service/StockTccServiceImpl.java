package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopsphere.api.product.dto.StockItem;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.product.entity.StockTccLogEntity;
import com.shopsphere.product.mapper.StockTccLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存 TCC 骨架实现（T2.4）。
 *
 * <h3>本期范围</h3>
 * 幂等表({@code t_stock_tcc_log})写入 + 直调 {@link StockRedisService}。幂等键
 * {@code (orderId, productId, phase)}:同 phase 重复 → 跳过(直接成功)。
 *
 * <h3>TODO(T3.3)</h3>
 * <ul>
 *   <li>加 Seata {@code @TwoPhaseBusinessAction} 注解,接入全局事务</li>
 *   <li>DB {@code t_product_stock} 条件更新:Try {@code stock-=q,locked+=q};
 *       Confirm {@code locked-=q};Cancel {@code stock+=q,locked-=q}</li>
 *   <li>完整空回滚(Cancel 先于 Try → 写 {@code state=0} 标记)</li>
 *   <li>防悬挂(Try 检测到已有 CANCEL 记录 → 拒绝,返回 3003)</li>
 *   <li>TCC 分支失败补偿 / Redis 与 DB 二阶段一致性</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockTccServiceImpl implements StockTccService {

    static final String PHASE_TRY = "TRY";
    static final String PHASE_CONFIRM = "CONFIRM";
    static final String PHASE_CANCEL = "CANCEL";
    static final int STATE_SUCCESS = 1;

    private final StockTccLogMapper tccLogMapper;
    private final StockRedisService stockRedisService;

    // TODO(T3.3): 加 @TwoPhaseBusinessAction;补 DB stock-=q/locked+=q;防悬挂;分支失败补偿。
    @Override
    @Transactional
    public void tryStock(StockTccDTO dto) {
        List<StockItem> items = dto.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }
        for (StockItem item : items) {
            if (logExists(dto.getOrderId(), item.getProductId(), PHASE_TRY)) {
                continue;   // 幂等：同 (order,product,TRY) 已处理
            }
            long remain = stockRedisService.preDeduct(item.getProductId(), item.getQuantity());
            if (remain == StockRedisService.RESULT_INSUFFICIENT) {
                throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
            }
            if (remain == StockRedisService.RESULT_KEY_MISSING) {
                throw new BusinessException(ErrorCode.STOCK_PREDEDUCT_FAIL);
            }
            insertLog(dto.getOrderId(), item.getProductId(), PHASE_TRY, item.getQuantity());
        }
    }

    // TODO(T3.3): Confirm 阶段补 DB locked_stock-=q 真实出库。
    @Override
    @Transactional
    public void confirmStock(StockTccActionDTO dto) {
        for (StockTccLogEntity tryRow : findTryRows(dto.getOrderId())) {
            if (logExists(dto.getOrderId(), tryRow.getProductId(), PHASE_CONFIRM)) {
                continue;
            }
            // Confirm 无 Redis 动作（Try 已扣，§4.3）
            insertLog(dto.getOrderId(), tryRow.getProductId(), PHASE_CONFIRM, tryRow.getQuantity());
        }
    }

    // TODO(T3.3): 完整空回滚（无 TRY 行 → 写 state=0 标记）/ 防悬挂 / DB stock+=q,locked-=q。
    @Override
    @Transactional
    public void cancelStock(StockTccActionDTO dto) {
        for (StockTccLogEntity tryRow : findTryRows(dto.getOrderId())) {
            if (logExists(dto.getOrderId(), tryRow.getProductId(), PHASE_CANCEL)) {
                continue;
            }
            stockRedisService.restore(tryRow.getProductId(), tryRow.getQuantity());
            insertLog(dto.getOrderId(), tryRow.getProductId(), PHASE_CANCEL, tryRow.getQuantity());
        }
    }

    private boolean logExists(Long orderId, Long productId, String phase) {
        return tccLogMapper.exists(new LambdaQueryWrapper<StockTccLogEntity>()
                .eq(StockTccLogEntity::getOrderId, orderId)
                .eq(StockTccLogEntity::getProductId, productId)
                .eq(StockTccLogEntity::getPhase, phase));
    }

    private List<StockTccLogEntity> findTryRows(Long orderId) {
        return tccLogMapper.selectList(new LambdaQueryWrapper<StockTccLogEntity>()
                .eq(StockTccLogEntity::getOrderId, orderId)
                .eq(StockTccLogEntity::getPhase, PHASE_TRY));
    }

    private void insertLog(Long orderId, Long productId, String phase, int quantity) {
        StockTccLogEntity row = StockTccLogEntity.builder()
                .orderId(orderId)
                .productId(productId)
                .phase(phase)
                .state(STATE_SUCCESS)
                .quantity(quantity)
                .build();
        try {
            tccLogMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发重复（uk_order_product_phase 命中）→ 幂等视作已处理
            log.warn("duplicate tcc log ignored: orderId={}, productId={}, phase={}",
                    orderId, productId, phase);
        }
    }
}
