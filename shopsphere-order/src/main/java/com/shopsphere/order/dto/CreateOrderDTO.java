package com.shopsphere.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 下单请求体（api-contracts §6.3）。{@code userId} 取自 {@code X-User-Id}，不在 body。
 */
@Data
public class CreateOrderDTO {

    @NotEmpty(message = "商品项不能为空")
    @Valid
    private List<OrderItemDTO> items;

    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    @Size(max = 255, message = "备注最长 255 字符")
    private String remark;
}
