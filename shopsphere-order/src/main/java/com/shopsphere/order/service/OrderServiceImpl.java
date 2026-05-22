package com.shopsphere.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.api.order.event.OrderCreatedEvent;
import com.shopsphere.api.order.event.OrderItemPayload;
import com.shopsphere.api.product.ProductFeignClient;
import com.shopsphere.api.product.dto.ProductDetailDTO;
import com.shopsphere.api.product.dto.StockItem;
import com.shopsphere.api.product.dto.StockTccActionDTO;
import com.shopsphere.api.product.dto.StockTccDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.common.result.Result;
import com.shopsphere.order.config.OrderProperties;
import com.shopsphere.order.constant.OrderConstants;
import com.shopsphere.order.dto.CreateOrderDTO;
import com.shopsphere.order.dto.OrderCreateVO;
import com.shopsphere.order.dto.OrderDetailVO;
import com.shopsphere.order.dto.OrderItemDTO;
import com.shopsphere.order.dto.OrderItemVO;
import com.shopsphere.order.dto.OrderTimeoutEvent;
import com.shopsphere.order.dto.OrderVO;
import com.shopsphere.order.entity.LocalMessageEntity;
import com.shopsphere.order.entity.OrderEntity;
import com.shopsphere.order.entity.OrderItemEntity;
import com.shopsphere.order.entity.OrderRequestEntity;
import com.shopsphere.order.enums.OrderStatus;
import com.shopsphere.order.mapper.OrderItemMapper;
import com.shopsphere.order.mapper.OrderMapper;
import com.shopsphere.order.mapper.OrderRequestMapper;
import com.shopsphere.order.statemachine.OrderStatusTransitionValidator;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单核心实现（api-contracts §6.3 / §4.3 / §8）。
 *
 * <p>{@link #createOrder}、{@link #pay}、{@link #cancel} 均为 {@code @GlobalTransactional} 发起方；
 * Feign 调用（stockTry / stockConfirm / stockCancel）一律置于本地 AT 写之后 —— 全局事务回滚经
 * undo_log 撤销 Order 侧，配合 Product 业务 TCC 的幂等保证一致。
 *
 * <p>所有改订单 {@code status} 的入口（{@link #pay}、{@link #cancel}）先经
 * {@link OrderStatusTransitionValidator} 统一校验状态机（§6.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CANCEL_LOCK_PREFIX = "cancel:lock:";
    private static final Duration CANCEL_LOCK_TTL = Duration.ofSeconds(60);

    private final ProductFeignClient productFeignClient;
    private final OrderPersistService orderPersistService;
    private final OrderRequestMapper orderRequestMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderProperties orderProperties;
    private final OrderStatusTransitionValidator statusValidator;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public OrderCreateVO findExistingOrder(Long userId, String requestId) {
        OrderRequestEntity req = orderRequestMapper.findByUserAndRequestId(userId, requestId);
        if (req == null) {
            return null;
        }
        OrderEntity order = orderMapper.selectById(req.getOrderId());
        if (order == null) {
            // 幂等记录与订单在同一本地事务写入，理论上必然存在；缺失视为数据异常
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return toCreateVO(order);
    }

    @Override
    @GlobalTransactional(name = "order-create", rollbackFor = Exception.class, timeoutMills = 60000)
    public OrderCreateVO createOrder(Long userId, String requestId, CreateOrderDTO dto) {
        String xid = RootContext.getXID();
        long orderId = IdWorker.getId();
        String orderNo = OrderConstants.ORDER_NO_PREFIX + orderId;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 合并请求中重复的 productId（累加 quantity），保持稳定顺序
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (OrderItemDTO item : dto.getItems()) {
            merged.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        // 逐个查商品（顺序调用：XID / UserContext 为 ThreadLocal，不能并行）+ 服务端计价
        List<OrderItemEntity> items = new ArrayList<>();
        List<StockItem> stockItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();
            Result<ProductDetailDTO> detail = productFeignClient.getDetail(productId);
            if (!detail.isSuccess() || detail.getData() == null) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在: " + productId);
            }
            ProductDetailDTO product = detail.getData();
            if (product.getStatus() == null || product.getStatus() != 1) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品已下架: " + productId);
            }
            totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            items.add(OrderItemEntity.builder()
                    .orderId(orderId)
                    .productId(productId)
                    .productName(product.getName())
                    .price(product.getPrice())
                    .quantity(quantity)
                    .build());
            stockItems.add(new StockItem(productId, quantity));
        }

        OffsetDateTime payExpireAt = now.plusMinutes(orderProperties.getPayment().getTimeoutMinutes());

        OrderEntity order = OrderEntity.builder()
                .id(orderId)
                .orderNo(orderNo)
                .userId(userId)
                .addressId(dto.getAddressId())
                .totalAmount(totalAmount)
                .status(OrderStatus.CREATED.getCode())
                .remark(dto.getRemark())
                .payExpireAt(payExpireAt)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // C3：order.created + order.payment.timeout 均写本地消息表（PENDING），由 outbox 中继投递
        List<LocalMessageEntity> localMessages = List.of(
                buildLocalMessage(orderNo, OrderConstants.RK_ORDER_CREATED,
                        orderCreatedPayload(order, items), now),
                buildLocalMessage(orderNo, OrderConstants.RK_ORDER_TIMEOUT,
                        paymentTimeoutPayload(orderId, payExpireAt), now));

        OrderRequestEntity orderRequest = OrderRequestEntity.builder()
                .userId(userId)
                .requestId(requestId)
                .orderId(orderId)
                .createdAt(now)
                .build();

        // 本地 AT 分支：建单 + 明细 + 2×outbox + 幂等记录，同一本地事务
        orderPersistService.persistOrder(order, items, localMessages, orderRequest);

        // 库存 TCC-Try（Feign → Product）放最后：失败则 @GlobalTransactional 回滚 Order 本地 AT 分支
        // （undo_log 撤销订单行），不留库存孤儿；不吞错。
        Result<Void> tryResult = productFeignClient.stockTry(new StockTccDTO(xid, orderId, stockItems));
        if (!tryResult.isSuccess()) {
            throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH,
                    tryResult.getMessage() != null ? tryResult.getMessage() : "库存预扣失败");
        }

        return toCreateVO(order);
    }

    @Override
    @GlobalTransactional(name = "order-pay", rollbackFor = Exception.class, timeoutMills = 60000)
    public OrderVO pay(Long userId, Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        // 状态机校验（§6.3）：仅 CREATED 可 → PAID，否则 4002
        statusValidator.assertCanTransit(OrderStatus.of(order.getStatus()), OrderStatus.PAID);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 本地 AT 分支：CREATED → PAID（条件更新防并发兜底）
        if (orderMapper.markPaid(orderId, now, now) == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "订单状态已变更");
        }
        // 库存 TCC-Confirm（Feign → Product）放最后：失败则全局回滚撤销 markPaid；不吞错
        Result<Void> confirm = productFeignClient.stockConfirm(
                new StockTccActionDTO(RootContext.getXID(), orderId));
        if (!confirm.isSuccess()) {
            throw new BusinessException(ErrorCode.SERVER_ERROR,
                    confirm.getMessage() != null ? confirm.getMessage() : "库存确认失败");
        }
        order.setStatus(OrderStatus.PAID.getCode());
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        return toOrderVO(order);
    }

    @Override
    public OrderDetailVO getDetail(Long userId, Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            // 不存在与非本人一律 4001，不暴露存在性
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        List<OrderItemVO> items = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItemEntity>()
                                .eq(OrderItemEntity::getOrderId, orderId))
                .stream()
                .map(i -> OrderItemVO.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .price(i.getPrice())
                        .quantity(i.getQuantity())
                        .build())
                .toList();
        return OrderDetailVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .status(OrderStatus.of(order.getStatus()).name())
                .totalAmount(order.getTotalAmount())
                .addressId(order.getAddressId())
                .remark(order.getRemark())
                .payExpireAt(order.getPayExpireAt())
                .paidAt(order.getPaidAt())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(items)
                .build();
    }

    @Override
    public PageResult<OrderVO> listOrders(Long userId, String status, int page, int size) {
        long current = page < 1 ? 1L : page;
        long pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        // 强制按 userId 过滤，防越权
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(OrderEntity::getStatus, parseStatus(status).getCode());
        }
        wrapper.orderByDesc(OrderEntity::getCreatedAt);
        Page<OrderEntity> result = orderMapper.selectPage(new Page<>(current, pageSize), wrapper);
        List<OrderVO> records = result.getRecords().stream().map(this::toOrderVO).toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @GlobalTransactional(name = "order-cancel", rollbackFor = Exception.class, timeoutMills = 60000)
    public OrderVO cancel(Long orderId, Long operatorUserId, String reason) {
        boolean systemTimeout = (operatorUserId == null);
        String lockKey = CANCEL_LOCK_PREFIX + orderId;
        // 防并发：同一订单的取消串行化（人工重复点击 / 人工与超时并发）
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", CANCEL_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "订单取消处理中，请勿重复提交");
        }
        try {
            OrderEntity order = orderMapper.selectById(orderId);
            if (order == null || (!systemTimeout && !order.getUserId().equals(operatorUserId))) {
                throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
            }
            // 状态机校验：SHIPPED/COMPLETED/已 CANCELLED → 4002
            statusValidator.assertCanTransit(OrderStatus.of(order.getStatus()), OrderStatus.CANCELLED);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            // 本地 AT 分支：条件更新。系统超时仅取消仍为 CREATED 的订单
            // （关闭「超时检查后用户支付」竞态）；人工取消允许 CREATED / PAID。
            int rows = systemTimeout
                    ? orderMapper.markCancelledIfCreated(orderId, now)
                    : orderMapper.markCancelled(orderId, now);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "订单状态已变更，无法取消");
            }
            // 库存回补（Feign → Product，置最后）：失败则全局回滚撤销 markCancelled；不吞错
            Result<Void> cancelResult = productFeignClient.stockCancel(
                    new StockTccActionDTO(RootContext.getXID(), orderId));
            if (!cancelResult.isSuccess()) {
                throw new BusinessException(ErrorCode.SERVER_ERROR,
                        cancelResult.getMessage() != null ? cancelResult.getMessage() : "库存回补失败");
            }
            order.setStatus(OrderStatus.CANCELLED.getCode());
            order.setUpdatedAt(now);
            log.info("订单取消成功 orderId={} reason={} operator={}",
                    orderId, reason, systemTimeout ? "system" : operatorUserId);
            return toOrderVO(order);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "非法订单状态: " + status);
        }
    }

    private OrderCreateVO toCreateVO(OrderEntity order) {
        return OrderCreateVO.builder()
                .orderId(order.getId())
                .status(OrderStatus.of(order.getStatus()).name())
                .totalAmount(order.getTotalAmount())
                .payExpireAt(order.getPayExpireAt())
                .build();
    }

    private OrderVO toOrderVO(OrderEntity order) {
        return OrderVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .status(OrderStatus.of(order.getStatus()).name())
                .totalAmount(order.getTotalAmount())
                .payExpireAt(order.getPayExpireAt())
                .paidAt(order.getPaidAt())
                .createdAt(order.getCreatedAt())
                .build();
    }

    private LocalMessageEntity buildLocalMessage(String orderNo, String routingKey,
                                                 String payload, OffsetDateTime now) {
        return LocalMessageEntity.builder()
                .bizKey(orderNo)
                .exchange(OrderConstants.EXCHANGE_ORDER)
                .routingKey(routingKey)
                .payload(payload)
                .status(OrderConstants.LOCAL_MSG_PENDING)
                .retryCount(0)
                .nextRetryAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * order.created 消息体（§8：orderId, orderNo, userId, items[], totalAmount, ts）。
     * 构造共享的 {@link OrderCreatedEvent}（order-api）后序列化 —— 与 User 消费端字段严格一致。
     */
    private String orderCreatedPayload(OrderEntity order, List<OrderItemEntity> items) {
        List<OrderItemPayload> itemPayloads = new ArrayList<>();
        for (OrderItemEntity item : items) {
            itemPayloads.add(OrderItemPayload.builder()
                    .productId(item.getProductId())
                    .productName(item.getProductName())
                    .price(item.getPrice())
                    .quantity(item.getQuantity())
                    .build());
        }
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .ts(order.getCreatedAt())
                .items(itemPayloads)
                .build();
        return toJson(event);
    }

    /** order.payment.timeout 消息体（§8：orderId, payExpireAt）。 */
    private String paymentTimeoutPayload(long orderId, OffsetDateTime payExpireAt) {
        return toJson(new OrderTimeoutEvent(orderId, payExpireAt));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "消息体序列化失败");
        }
    }
}
