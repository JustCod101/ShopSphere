package com.shopsphere.e2e.support;

import com.shopsphere.e2e.E2eConfig;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;

import java.time.Duration;

/**
 * Awaitility 预置等待器：所有 MQ/异步断言用同一形态（默认 10s，超时 case 60s）。
 */
public final class Awaits {
    private Awaits() {}

    public static ConditionFactory defaultAwait() {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(E2eConfig.get().defaultAwaitSec()))
                .pollInterval(Duration.ofMillis(200))
                .pollDelay(Duration.ZERO);
    }

    public static ConditionFactory longAwait() {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(E2eConfig.get().timeoutAwaitSec()))
                .pollInterval(Duration.ofSeconds(1))
                .pollDelay(Duration.ZERO);
    }
}
