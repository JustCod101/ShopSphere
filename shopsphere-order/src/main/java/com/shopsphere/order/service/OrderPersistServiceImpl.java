package com.shopsphere.order.service;

import com.shopsphere.order.entity.LocalMessageEntity;
import com.shopsphere.order.entity.OrderEntity;
import com.shopsphere.order.entity.OrderItemEntity;
import com.shopsphere.order.entity.OrderRequestEntity;
import com.shopsphere.order.mapper.LocalMessageMapper;
import com.shopsphere.order.mapper.OrderItemMapper;
import com.shopsphere.order.mapper.OrderMapper;
import com.shopsphere.order.mapper.OrderRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 下单本地落库。{@code @Transactional} 即 Order 在全局事务下的本地 AT 分支
 * （Order 库已建 {@code undo_log}）。
 *
 * <p>独立于 {@code OrderServiceImpl}：避免 {@code @GlobalTransactional} 方法自调用绕过
 * {@code @Transactional} 代理；且本地事务边界紧贴四类写入、不含 Feign 调用。
 */
@Service
@RequiredArgsConstructor
public class OrderPersistServiceImpl implements OrderPersistService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final LocalMessageMapper localMessageMapper;
    private final OrderRequestMapper orderRequestMapper;

    @Override
    @Transactional
    public void persistOrder(OrderEntity order, List<OrderItemEntity> items,
                             List<LocalMessageEntity> localMessages, OrderRequestEntity orderRequest) {
        orderMapper.insert(order);
        for (OrderItemEntity item : items) {
            orderItemMapper.insert(item);
        }
        // C3：order.created / order.payment.timeout 与建单同一本地事务，杜绝丢消息
        for (LocalMessageEntity msg : localMessages) {
            localMessageMapper.insert(msg);
        }
        // S5：幂等记录；命中 uk_user_req → DuplicateKeyException → 本地 + 全局事务回滚
        orderRequestMapper.insert(orderRequest);
    }
}
