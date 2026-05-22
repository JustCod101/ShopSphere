package com.shopsphere.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopsphere.api.product.dto.StockItem;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.product.entity.StockTccLogEntity;
import com.shopsphere.product.mapper.ProductStockMapper;
import com.shopsphere.product.mapper.StockTccLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 库存 TCC 实现（业务 TCC，T3.3）。
 *
 * <p>try/confirm/cancel 为 3 个显式幂等操作，<b>非 Seata {@code @TwoPhaseBusinessAction}</b> ——
 * Confirm 须在支付时由独立 {@code /pay} 触发，而 Seata 二阶段回调在「运行 Try 的全局事务」提交时
 * 即触发，无法延迟到另一请求。每个方法 {@code @Transactional}：{@code t_product_stock} 条件更新
 * 与 {@code t_stock_tcc_log} 幂等日志在同一本地事务（契约 §4.3）。
 *
 * <p>{@code stock} 列 = 可售库存池，Redis {@code stock:product:{id}} 镜像之；
 * 真实总量 = {@code stock + locked_stock}。
 *
 * <h3>幂等 / 空回滚 / 防悬挂（§4.3）</h3>
 * <ul>
 *   <li>幂等键 {@code (orderId, productId, phase)}：各阶段重复调用只生效一次。</li>
 *   <li>空回滚：Cancel 先于 Try 到达 → 写订单级标记 {@code (orderId, 0, CANCEL, state=0)}，不动库存。</li>
 *   <li>防悬挂：Try 检测到该订单已有 CANCEL 记录（订单级或商品级）→ 拒绝，返回 3003。</li>
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
    static final int STATE_EMPTY_ROLLBACK = 0;
    /** 订单级空回滚标记的哨兵 productId（Cancel 端点无 items，空回滚按订单维度记录）。 */
    static final long ORDER_MARKER_PRODUCT_ID = 0L;

    private final StockTccLogMapper tccLogMapper;
    private final StockRedisService stockRedisService;
    private final ProductStockMapper productStockMapper;

    @Override
    @Transactional
    public void tryStock(StockTccDTO dto) {
        List<StockItem> items = dto.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }
        Long orderId = dto.getOrderId();
        // 订单级防悬挂：该订单已有空回滚标记 → 拒绝 Try
        if (logExists(orderId, ORDER_MARKER_PRODUCT_ID, PHASE_CANCEL)) {
            throw new BusinessException(ErrorCode.STOCK_PREDEDUCT_FAIL, "订单已取消，拒绝预扣（防悬挂）");
        }
        // 记录本次调用已 Redis 预扣的项，用于多商品部分失败时显式回补
        List<StockItem> compensated = new ArrayList<>();
        try {
            for (StockItem item : items) {
                Long productId = item.getProductId();
                int qty = item.getQuantity();
                // 商品级防悬挂
                if (logExists(orderId, productId, PHASE_CANCEL)) {
                    throw new BusinessException(ErrorCode.STOCK_PREDEDUCT_FAIL,
                            "商品预留已取消，拒绝预扣（防悬挂）: " + productId);
                }
                // 幂等：同 (order,product,TRY) 已处理 → 跳过
                if (logExists(orderId, productId, PHASE_TRY)) {
                    continue;
                }
                // Redis 原子预扣
                long remain = stockRedisService.preDeduct(productId, qty);
                if (remain == StockRedisService.RESULT_INSUFFICIENT) {
                    throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
                }
                if (remain == StockRedisService.RESULT_KEY_MISSING) {
                    throw new BusinessException(ErrorCode.STOCK_PREDEDUCT_FAIL);
                }
                compensated.add(item);
                // DB 条件扣减：stock-=q, locked+=q WHERE stock>=q
                if (productStockMapper.tryDeduct(productId, qty) == 0) {
                    throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
                }
                insertLog(orderId, productId, PHASE_TRY, qty, STATE_SUCCESS);
            }
        } catch (RuntimeException e) {
            // 多商品部分失败：已 Redis 预扣的项显式回补（DB 由 @Transactional 回滚）；补偿后重抛，不吞错
            for (StockItem done : compensated) {
                stockRedisService.restore(done.getProductId(), done.getQuantity());
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public void confirmStock(StockTccActionDTO dto) {
        Long orderId = dto.getOrderId();
        for (StockTccLogEntity tryRow : findTryRows(orderId)) {
            Long productId = tryRow.getProductId();
            if (logExists(orderId, productId, PHASE_CONFIRM)) {
                continue;   // 幂等
            }
            int qty = tryRow.getQuantity();
            // 真实出库：locked_stock-=q（Redis 不动 —— Try 已扣，§4.3）
            if (productStockMapper.confirm(productId, qty) == 0) {
                log.error("stock confirm failed: locked_stock 不足, orderId={}, productId={}, qty={}",
                        orderId, productId, qty);
                throw new BusinessException(ErrorCode.SERVER_ERROR, "库存确认失败：预留不足");
            }
            insertLog(orderId, productId, PHASE_CONFIRM, qty, STATE_SUCCESS);
        }
    }

    @Override
    @Transactional
    public void cancelStock(StockTccActionDTO dto) {
        Long orderId = dto.getOrderId();
        List<StockTccLogEntity> tryRows = findTryRows(orderId);
        if (tryRows.isEmpty()) {
            // 空回滚：Cancel 先于 Try（Try 失败/未到达）→ 写订单级标记，不动库存（防悬挂依据）
            if (!logExists(orderId, ORDER_MARKER_PRODUCT_ID, PHASE_CANCEL)) {
                insertLog(orderId, ORDER_MARKER_PRODUCT_ID, PHASE_CANCEL, 0, STATE_EMPTY_ROLLBACK);
                log.info("stock cancel empty-rollback marked: orderId={}", orderId);
            }
            return;
        }
        for (StockTccLogEntity tryRow : tryRows) {
            Long productId = tryRow.getProductId();
            if (logExists(orderId, productId, PHASE_CANCEL)) {
                continue;   // 幂等
            }
            int qty = tryRow.getQuantity();
            if (logExists(orderId, productId, PHASE_CONFIRM)) {
                // 已 Confirm（订单已支付）→ 逆向补偿：已实扣的数量退回可售池（locked 已归零）
                productStockMapper.cancelConfirmed(productId, qty);
            } else if (productStockMapper.cancel(productId, qty) == 0) {
                // 未 Confirm 但预留释放影响 0 行 —— locked_stock 不足属数据异常，记 WARN 跳过
                log.warn("stock cancel skipped: locked_stock 不足, orderId={}, productId={}, qty={}",
                        orderId, productId, qty);
                continue;
            }
            stockRedisService.restore(productId, qty);   // 显式回补 Redis（§4.3）
            insertLog(orderId, productId, PHASE_CANCEL, qty, STATE_SUCCESS);
        }
    }

    private boolean logExists(Long orderId, long productId, String phase) {
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

    private void insertLog(Long orderId, long productId, String phase, int quantity, int state) {
        StockTccLogEntity row = StockTccLogEntity.builder()
                .orderId(orderId)
                .productId(productId)
                .phase(phase)
                .state(state)
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
