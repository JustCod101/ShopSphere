package com.shopsphere.e2e.support;

import com.shopsphere.e2e.E2eConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * 所有 E2E 测试类的基类：@BeforeEach truncate + 暴露 shared 工具。
 * 子类 @Tag("e2e")—— 默认 included；case h 额外加 @Tag("timeout")。
 */
@Tag("e2e")
public abstract class E2eBase {

    protected final E2eConfig cfg = E2eConfig.get();
    protected final DbFixtures db = new DbFixtures();

    @BeforeEach
    void beforeEach() {
        db.truncateAndReset();
    }
}
