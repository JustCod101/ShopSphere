package com.shopsphere.order.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import com.shopsphere.order.dto.CreateOrderDTO;
import com.shopsphere.order.dto.OrderCreateVO;
import com.shopsphere.order.dto.OrderDetailVO;
import com.shopsphere.order.dto.OrderItemDTO;
import com.shopsphere.order.dto.OrderVO;
import com.shopsphere.order.entity.LocalMessageEntity;
import com.shopsphere.order.entity.OrderEntity;
import com.shopsphere.order.entity.OrderItemEntity;
import com.shopsphere.order.entity.OrderRequestEntity;
import com.shopsphere.order.mapper.OrderItemMapper;
import com.shopsphere.order.mapper.OrderMapper;
import com.shopsphere.order.mapper.OrderRequestMapper;
import com.shopsphere.order.statemachine.OrderStatusTransitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 单测（Mockito）。{@code @GlobalTransactional} 在无 Seata 容器的单测中为 no-op，
 * 此处验证下单 / 支付 / 查询 / 取消的编排逻辑与状态机校验。
 */
class OrderServiceImplTest {

    private ProductFeignClient productFeignClient;
    private OrderPersistService orderPersistService;
    private OrderRequestMapper orderRequestMapper;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private StringRedisTemplate stringRedisTemplate;
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        productFeignClient = mock(ProductFeignClient.class);
        orderPersistService = mock(OrderPersistService.class);
        orderRequestMapper = mock(OrderRequestMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new OrderServiceImpl(productFeignClient, orderPersistService,
                orderRequestMapper, orderMapper, orderItemMapper, new OrderProperties(),
                new OrderStatusTransitionValidator(), stringRedisTemplate, objectMapper);
    }

    private static ProductDetailDTO product(long id, String price, int status) {
        return ProductDetailDTO.builder()
                .id(id).name("product-" + id).price(new BigDecimal(price)).status(status).build();
    }

    private static OrderItemDTO item(long productId, int qty) {
        OrderItemDTO i = new OrderItemDTO();
        i.setProductId(productId);
        i.setQuantity(qty);
        return i;
    }

    private static CreateOrderDTO dto(OrderItemDTO... items) {
        CreateOrderDTO d = new CreateOrderDTO();
        d.setItems(List.of(items));
        d.setAddressId(1001L);
        d.setRemark("test");
        return d;
    }

    private static OrderEntity orderOf(long userId, int status) {
        return OrderEntity.builder()
                .id(9001L).orderNo("SO9001").userId(userId).status(status)
                .addressId(1001L).totalAmount(new BigDecimal("20.00"))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
    }

    /** 桩 cancel 的 Redis 防并发锁获取结果。 */
    private void givenCancelLock(boolean acquired) {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(acquired);
    }

    // ---------------------- createOrder ----------------------

    @Test
    @SuppressWarnings("unchecked")
    void createOrder_happyPath_computesTotalAndPersists() {
        when(productFeignClient.getDetail(2001L)).thenReturn(Result.ok(product(2001L, "10.00", 1)));
        when(productFeignClient.stockTry(any(StockTccDTO.class))).thenReturn(Result.ok());

        OrderCreateVO vo = service.createOrder(1L, "req-1", dto(item(2001L, 2)));

        assertEquals("CREATED", vo.getStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getTotalAmount()));
        assertNotNull(vo.getOrderId());
        assertNotNull(vo.getPayExpireAt());

        ArgumentCaptor<OrderEntity> orderCap = ArgumentCaptor.forClass(OrderEntity.class);
        ArgumentCaptor<List<LocalMessageEntity>> msgCap = ArgumentCaptor.forClass(List.class);
        verify(orderPersistService).persistOrder(orderCap.capture(), anyList(),
                msgCap.capture(), any(OrderRequestEntity.class));
        assertEquals(0, orderCap.getValue().getStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(orderCap.getValue().getTotalAmount()));
        assertEquals(2, msgCap.getValue().size());   // order.created + order.payment.timeout
    }

    @Test
    void createOrder_stockTryFails_throwsStockNotEnough() {
        when(productFeignClient.getDetail(2001L)).thenReturn(Result.ok(product(2001L, "10.00", 1)));
        when(productFeignClient.stockTry(any(StockTccDTO.class)))
                .thenReturn(Result.fail(ErrorCode.STOCK_PREDEDUCT_FAIL));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOrder(1L, "req-1", dto(item(2001L, 999999))));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, ex.getErrorCode());
        verify(orderPersistService).persistOrder(any(), any(), any(), any());
    }

    @Test
    void createOrder_productNotFound_throws_noStockTry() {
        when(productFeignClient.getDetail(9999L)).thenReturn(Result.fail(ErrorCode.PRODUCT_NOT_FOUND));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOrder(1L, "req-1", dto(item(9999L, 1))));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
        verify(productFeignClient, never()).stockTry(any());
        verify(orderPersistService, never()).persistOrder(any(), any(), any(), any());
    }

    @Test
    void createOrder_productOffShelf_throwsProductNotFound() {
        when(productFeignClient.getDetail(2001L)).thenReturn(Result.ok(product(2001L, "10.00", 0)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOrder(1L, "req-1", dto(item(2001L, 1))));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createOrder_mergesDuplicateProductIds() {
        when(productFeignClient.getDetail(2001L)).thenReturn(Result.ok(product(2001L, "10.00", 1)));
        when(productFeignClient.stockTry(any(StockTccDTO.class))).thenReturn(Result.ok());

        service.createOrder(1L, "req-1", dto(item(2001L, 2), item(2001L, 3)));

        verify(productFeignClient, times(1)).getDetail(2001L);
        ArgumentCaptor<StockTccDTO> tccCap = ArgumentCaptor.forClass(StockTccDTO.class);
        verify(productFeignClient).stockTry(tccCap.capture());
        List<StockItem> stockItems = tccCap.getValue().getItems();
        assertEquals(1, stockItems.size());
        assertEquals(5, stockItems.get(0).getQuantity());
    }

    // ---------------------- findExistingOrder ----------------------

    @Test
    void findExistingOrder_returnsFirstOrderVo() {
        when(orderRequestMapper.findByUserAndRequestId(1L, "req-1"))
                .thenReturn(OrderRequestEntity.builder().orderId(9001L).build());
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));

        OrderCreateVO vo = service.findExistingOrder(1L, "req-1");
        assertNotNull(vo);
        assertEquals(9001L, vo.getOrderId());
        assertEquals("CREATED", vo.getStatus());
    }

    @Test
    void findExistingOrder_returnsNullWhenAbsent() {
        when(orderRequestMapper.findByUserAndRequestId(1L, "req-x")).thenReturn(null);
        assertNull(service.findExistingOrder(1L, "req-x"));
    }

    // ---------------------- pay ----------------------

    @Test
    void pay_success_marksPaidAndConfirmsStock() {
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));
        when(orderMapper.markPaid(eq(9001L), any(), any())).thenReturn(1);
        when(productFeignClient.stockConfirm(any(StockTccActionDTO.class))).thenReturn(Result.ok());

        OrderVO vo = service.pay(1L, 9001L);

        assertEquals("PAID", vo.getStatus());
        verify(orderMapper).markPaid(eq(9001L), any(), any());
        verify(productFeignClient).stockConfirm(any(StockTccActionDTO.class));
    }

    @Test
    void pay_orderNotFound_throws() {
        when(orderMapper.selectById(9001L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay(1L, 9001L));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void pay_notOwner_throwsOrderNotFound() {
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(2L, 0));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay(1L, 9001L));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void pay_notCreatedStatus_throwsStatusInvalid() {
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 1));   // 已 PAID
        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay(1L, 9001L));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.getErrorCode());
    }

    @Test
    void pay_stockConfirmFails_throws() {
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));
        when(orderMapper.markPaid(eq(9001L), any(), any())).thenReturn(1);
        when(productFeignClient.stockConfirm(any(StockTccActionDTO.class)))
                .thenReturn(Result.fail(ErrorCode.SERVER_ERROR));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay(1L, 9001L));
        assertEquals(ErrorCode.SERVER_ERROR, ex.getErrorCode());
    }

    // ---------------------- getDetail ----------------------

    @Test
    void getDetail_returnsDetailWithItems() {
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                OrderItemEntity.builder().orderId(9001L).productId(2001L)
                        .productName("p-2001").price(new BigDecimal("10.00")).quantity(2).build()));

        OrderDetailVO vo = service.getDetail(1L, 9001L);

        assertEquals(9001L, vo.getOrderId());
        assertEquals("CREATED", vo.getStatus());
        assertEquals(1, vo.getItems().size());
        assertEquals(2001L, vo.getItems().get(0).getProductId());
    }

    @Test
    void getDetail_notFound_throws() {
        when(orderMapper.selectById(9001L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getDetail(1L, 9001L));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getDetail_notOwner_throwsOrderNotFound() {
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(2L, 0));   // 属于用户 2
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getDetail(1L, 9001L));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    // ---------------------- listOrders ----------------------

    @Test
    void listOrders_returnsPageMappedToVo() {
        Page<OrderEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(orderOf(1L, 0)));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<OrderVO> result = service.listOrders(1L, null, 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("CREATED", result.getRecords().get(0).getStatus());
    }

    @Test
    void listOrders_invalidStatus_throwsParamInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listOrders(1L, "BOGUS", 1, 20));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    // ---------------------- cancel ----------------------

    @Test
    void cancel_created_success_restoresStock() {
        givenCancelLock(true);
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));
        when(orderMapper.markCancelled(eq(9001L), any())).thenReturn(1);
        when(productFeignClient.stockCancel(any(StockTccActionDTO.class))).thenReturn(Result.ok());

        OrderVO vo = service.cancel(9001L, 1L, "user");

        assertEquals("CANCELLED", vo.getStatus());
        verify(orderMapper).markCancelled(eq(9001L), any());
        verify(productFeignClient).stockCancel(any(StockTccActionDTO.class));
    }

    @Test
    void cancel_lockNotAcquired_throws() {
        givenCancelLock(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(9001L, 1L, "user"));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.getErrorCode());
        verify(orderMapper, never()).selectById(any());
    }

    @Test
    void cancel_notOwner_throwsOrderNotFound() {
        givenCancelLock(true);
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(2L, 0));   // 属于用户 2
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(9001L, 1L, "user"));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void cancel_shippedOrder_throwsStatusInvalid() {
        givenCancelLock(true);
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 2));   // SHIPPED
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(9001L, 1L, "user"));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.getErrorCode());
        verify(orderMapper, never()).markCancelled(any(), any());
    }

    @Test
    void cancel_systemTimeout_usesMarkCancelledIfCreated() {
        givenCancelLock(true);
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));
        when(orderMapper.markCancelledIfCreated(eq(9001L), any())).thenReturn(1);
        when(productFeignClient.stockCancel(any(StockTccActionDTO.class))).thenReturn(Result.ok());

        service.cancel(9001L, null, "system-timeout");   // operatorUserId=null → 系统超时

        verify(orderMapper).markCancelledIfCreated(eq(9001L), any());
        verify(orderMapper, never()).markCancelled(any(), any());
    }

    @Test
    void cancel_conditionalUpdateZeroRows_throwsStatusInvalid() {
        givenCancelLock(true);
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 0));
        when(orderMapper.markCancelled(eq(9001L), any())).thenReturn(0);   // 并发已变更
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(9001L, 1L, "user"));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.getErrorCode());
        verify(productFeignClient, never()).stockCancel(any());
    }

    @Test
    void cancel_stockCancelFails_throws() {
        givenCancelLock(true);
        when(orderMapper.selectById(9001L)).thenReturn(orderOf(1L, 1));   // PAID 可取消
        when(orderMapper.markCancelled(eq(9001L), any())).thenReturn(1);
        when(productFeignClient.stockCancel(any(StockTccActionDTO.class)))
                .thenReturn(Result.fail(ErrorCode.SERVER_ERROR));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(9001L, 1L, "user"));
        assertEquals(ErrorCode.SERVER_ERROR, ex.getErrorCode());
    }
}
