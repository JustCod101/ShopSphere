package com.shopsphere.product.service;

import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;

/**
 * 库存 TCC 服务（T2.4 骨架）。
 *
 * <p>本期 = 幂等表写入 + 直调 {@code StockRedisService};完整 Seata
 * {@code @TwoPhaseBusinessAction}、空回滚、防悬挂、DB {@code t_product_stock}
 * 条件更新留到 T3.3。
 */
public interface StockTccService {

    /** TCC-Try：按 items 预扣库存,幂等。库存不足抛 {@code STOCK_NOT_ENOUGH}。 */
    void tryStock(StockTccDTO dto);

    /** TCC-Confirm：真实出库,按 orderId 对全部预留项确认,幂等。 */
    void confirmStock(StockTccActionDTO dto);

    /** TCC-Cancel：释放并回补,按 orderId 对全部预留项取消,幂等。 */
    void cancelStock(StockTccActionDTO dto);
}
