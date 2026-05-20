package com.shopsphere.user.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务监听桥：仅在 t_user_behavior insert 所在事务 <b>提交后</b> 才异步发 MQ。
 *
 * <p>避免"消费侧收到事件却查不到 User 自审计行"假象（虽然推荐侧契约上不查 User 库，
 * 但事务可见性铁律仍按 §8 工程纪律执行）。
 *
 * <p>真正的 RabbitTemplate 调用在 {@link BehaviorEventPublisher#publish}（{@code @Async}）。
 */
@Component
@RequiredArgsConstructor
public class BehaviorEventListener {

    private final BehaviorEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommit(BehaviorEvent event) {
        publisher.publish(event);
    }
}
