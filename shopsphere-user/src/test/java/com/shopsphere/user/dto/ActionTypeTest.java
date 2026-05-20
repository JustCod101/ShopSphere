package com.shopsphere.user.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ActionType Jackson 序列化/反序列化往返、大小写宽容、非法值抛 IAE（→ 经 GlobalExceptionHandler 转 1000）。
 */
class ActionTypeTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void serializesAsLowerCaseName() throws Exception {
        assertEquals("\"view\"", om.writeValueAsString(ActionType.VIEW));
        assertEquals("\"cart\"", om.writeValueAsString(ActionType.CART));
        assertEquals("\"order\"", om.writeValueAsString(ActionType.ORDER));
    }

    @Test
    void deserializesLowercaseUppercaseMixed() throws Exception {
        assertEquals(ActionType.VIEW, om.readValue("\"view\"", ActionType.class));
        assertEquals(ActionType.VIEW, om.readValue("\"VIEW\"", ActionType.class));
        assertEquals(ActionType.CART, om.readValue("\"Cart\"", ActionType.class));
        assertEquals(ActionType.ORDER, om.readValue("\" order \"", ActionType.class)); // 含空白
    }

    @Test
    void invalidActionTypeThrowsIAE_withDescriptiveMessage() {
        Exception ex = assertThrows(Exception.class,
                () -> om.readValue("\"unknown\"", ActionType.class));
        // Jackson 把 IAE 包成 ValueInstantiationException → cause 是 IAE
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertInstanceOf(IllegalArgumentException.class, root);
        assertTrue(root.getMessage().contains("view/cart/order"),
                "message 须包含合法值列表，便于客户端定位");
    }

    @Test
    void nullJsonStringThrowsIAE() {
        // ActionType.fromJson(null) 直接抛 IAE；Jackson 在 JSON null 时一般不会调到 @JsonCreator，
        // 这里直接走方法验证防御分支
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActionType.fromJson(null));
        assertTrue(ex.getMessage().contains("不能为空"));
    }
}
