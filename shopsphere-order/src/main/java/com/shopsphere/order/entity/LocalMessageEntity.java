package com.shopsphere.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * t_local_message 实体（本地消息表 / outbox，C3）。
 *
 * <p>{@code order.created} / {@code order.payment.timeout} 与建单在同一本地事务内 INSERT，
 * 保证订单与消息原子。{@code status}：0=PENDING 1=SENT 2=CONFIRMED 3=FAILED。
 * <p>PENDING 行由后续 outbox 中继任务投递并改状态（非 T3.2 范围）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_local_message")
public class LocalMessageEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务键（如订单号），便于排查与消费侧幂等 */
    @TableField("biz_key")
    private String bizKey;

    private String exchange;

    @TableField("routing_key")
    private String routingKey;

    /** 消息体 JSON */
    private String payload;

    /** 0=PENDING 1=SENT 2=CONFIRMED 3=FAILED */
    private Integer status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("next_retry_at")
    private OffsetDateTime nextRetryAt;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;
}
