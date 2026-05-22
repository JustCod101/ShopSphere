package com.shopsphere.user.service;

import com.shopsphere.api.order.event.OrderCreatedEvent;
import com.shopsphere.user.entity.PointsLogEntity;
import com.shopsphere.user.mapper.PointsLogMapper;
import com.shopsphere.user.mapper.UserPointsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 积分发放（T3.4，契约 §8）。积分规则：{@code points = floor(totalAmount * 1)}。
 *
 * <p>{@code award} 在单个 {@code @Transactional} 内同时写 {@code t_points_log}（幂等键）与
 * {@code t_user_points}（业务）—— 满足约束「幂等键写入与业务操作必须同事务」。
 * 先写 {@code t_points_log}：命中 {@code uk_order} → {@code DuplicateKeyException} → 整事务回滚，
 * 积分不被重复累加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsServiceImpl implements PointsService {

    private final UserPointsMapper userPointsMapper;
    private final PointsLogMapper pointsLogMapper;

    @Override
    @Transactional
    public void award(OrderCreatedEvent event) {
        long points = event.getTotalAmount().setScale(0, RoundingMode.FLOOR).longValueExact();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 幂等键先行：重复消费时此处即抛 DuplicateKeyException，addPoints 不会执行
        pointsLogMapper.insert(PointsLogEntity.builder()
                .orderId(event.getOrderId())
                .userId(event.getUserId())
                .points(Math.toIntExact(points))
                .createdAt(now)
                .build());
        userPointsMapper.addPoints(event.getUserId(), points, now);

        log.info("积分发放 orderId={} userId={} points={}",
                event.getOrderId(), event.getUserId(), points);
    }
}
