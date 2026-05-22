package com.shopsphere.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.order.entity.LocalMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessageEntity> {

    /**
     * 取一批待投递消息（PENDING 且到期）。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}：多实例并发扫描时，已被其他实例事务锁住的行直接跳过，
     * 各实例取到互斥的行集，杜绝重复投递。行锁持有至调用方事务提交。
     */
    @Select("SELECT * FROM t_local_message "
            + "WHERE status = 0 AND (next_retry_at IS NULL OR next_retry_at <= #{now}) "
            + "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<LocalMessageEntity> selectPendingBatch(@Param("now") OffsetDateTime now,
                                                @Param("limit") int limit);

    /** PENDING(0) → SENT(1)。仅在仍为 PENDING 时生效。 */
    @Update("UPDATE t_local_message SET status = 1, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** SENT(1) → CONFIRMED(2)。broker confirm ack=true 时调用。 */
    @Update("UPDATE t_local_message SET status = 2, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 1")
    int markConfirmed(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** 退避重试：回 PENDING(0) 并累加 retry_count、设置 next_retry_at。 */
    @Update("UPDATE t_local_message SET status = 0, retry_count = #{retryCount}, "
            + "next_retry_at = #{nextRetryAt}, updated_at = #{now} WHERE id = #{id}")
    int markRetry(@Param("id") Long id, @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") OffsetDateTime nextRetryAt, @Param("now") OffsetDateTime now);

    /** 重试耗尽 → FAILED(3)。 */
    @Update("UPDATE t_local_message SET status = 3, retry_count = #{retryCount}, "
            + "updated_at = #{now} WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("retryCount") int retryCount,
                   @Param("now") OffsetDateTime now);
}
