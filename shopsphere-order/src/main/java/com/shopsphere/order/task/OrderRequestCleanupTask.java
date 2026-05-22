package com.shopsphere.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopsphere.order.entity.OrderRequestEntity;
import com.shopsphere.order.mapper.OrderRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 下单幂等表清理（S5，TTL 24h）。每日删除 {@code created_at} 早于 24h 的 {@code t_order_request} 记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRequestCleanupTask {

    private static final int TTL_HOURS = 24;

    private final OrderRequestMapper orderRequestMapper;

    @Scheduled(cron = "${order.request.cleanup-cron:0 0 4 * * *}")
    public void purgeExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusHours(TTL_HOURS);
        int removed = orderRequestMapper.delete(new LambdaQueryWrapper<OrderRequestEntity>()
                .lt(OrderRequestEntity::getCreatedAt, cutoff));
        log.info("t_order_request 幂等表清理：删除 {} 条早于 {} 的记录", removed, cutoff);
    }
}
