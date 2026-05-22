package com.shopsphere.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopsphere.common.context.UserContext;
import com.shopsphere.common.context.UserContextHolder;
import com.shopsphere.common.exception.GlobalExceptionHandler;
import com.shopsphere.common.result.PageResult;
import com.shopsphere.order.dto.OrderCreateVO;
import com.shopsphere.order.dto.OrderDetailVO;
import com.shopsphere.order.dto.OrderVO;
import com.shopsphere.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderController 单测（standalone MockMvc，mock OrderService）。
 * 覆盖 X-Request-Id 校验、请求体校验、S5 幂等重放。
 */
class OrderControllerTest {

    private OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        UserContextHolder.set(UserContext.builder().userId(1L).build());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void create_missingRequestId_returns1000() throws Exception {
        mockMvc.perform(post("/api/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":2001,\"quantity\":1}],\"addressId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        verify(orderService, never()).createOrder(any(), any(), any());
    }

    @Test
    void create_emptyItems_returns1000() throws Exception {
        mockMvc.perform(post("/api/order/create")
                        .header("X-Request-Id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[],\"addressId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        verify(orderService, never()).createOrder(any(), any(), any());
    }

    @Test
    void create_idempotentReplay_returnsExistingWithoutCreate() throws Exception {
        OrderCreateVO existing = OrderCreateVO.builder()
                .orderId(9001L).status("CREATED").totalAmount(new BigDecimal("20.00"))
                .payExpireAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        when(orderService.findExistingOrder(any(), eq("req-1"))).thenReturn(existing);

        mockMvc.perform(post("/api/order/create")
                        .header("X-Request-Id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":2001,\"quantity\":1}],\"addressId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderId").value(9001));
        verify(orderService, never()).createOrder(any(), any(), any());
    }

    @Test
    void pay_returnsOrderVoWithNewStatus() throws Exception {
        OrderVO vo = OrderVO.builder()
                .orderId(9001L).orderNo("SO9001").status("PAID")
                .totalAmount(new BigDecimal("20.00")).build();
        when(orderService.pay(any(), eq(9001L))).thenReturn(vo);

        mockMvc.perform(post("/api/order/9001/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderId").value(9001))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    void detail_returnsOrderDetailWithItems() throws Exception {
        OrderDetailVO detail = OrderDetailVO.builder()
                .orderId(9001L).orderNo("SO9001").status("CREATED")
                .totalAmount(new BigDecimal("20.00")).items(List.of()).build();
        when(orderService.getDetail(eq(1L), eq(9001L))).thenReturn(detail);

        mockMvc.perform(get("/api/order/9001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderId").value(9001))
                .andExpect(jsonPath("$.data.status").value("CREATED"));
    }

    @Test
    void list_returnsPageResult() throws Exception {
        OrderVO vo = OrderVO.builder().orderId(9001L).orderNo("SO9001").status("CREATED").build();
        when(orderService.listOrders(eq(1L), eq("CREATED"), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(vo), 1, 1, 20));

        mockMvc.perform(get("/api/order/list").param("status", "CREATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].orderId").value(9001));
    }

    @Test
    void cancel_returnsCancelledOrder() throws Exception {
        OrderVO vo = OrderVO.builder()
                .orderId(9001L).orderNo("SO9001").status("CANCELLED").build();
        when(orderService.cancel(eq(9001L), eq(1L), any())).thenReturn(vo);

        mockMvc.perform(post("/api/order/9001/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
