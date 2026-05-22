package com.shopsphere.order.service;

import com.shopsphere.order.entity.LocalMessageEntity;
import com.shopsphere.order.entity.OrderEntity;
import com.shopsphere.order.entity.OrderItemEntity;
import com.shopsphere.order.entity.OrderRequestEntity;

import java.util.List;

public interface OrderPersistService {

    /**
     * 下单本地落库（Order 本地 AT 分支）：建单 + 明细 + outbox 消息 + 幂等记录，
     * <b>全部同一本地事务</b>（C3：本地消息与建单原子）。
     */
    void persistOrder(OrderEntity order, List<OrderItemEntity> items,
                      List<LocalMessageEntity> localMessages, OrderRequestEntity orderRequest);
}
