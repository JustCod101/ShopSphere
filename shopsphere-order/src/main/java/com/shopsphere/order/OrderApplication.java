package com.shopsphere.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务启动类（端口 8083）。
 *
 * <p>下单链路发起方：{@code POST /api/order/create} 以 {@code @GlobalTransactional} 开启全局事务，
 * 经 Feign 调 Product 查商品 + 库存 TCC-Try。
 *
 * <p>{@code @EnableFeignClients} 仅扫描 {@code com.shopsphere.api.product}（{@code ProductFeignClient}）。
 * {@code @EnableScheduling} 供 {@code OrderRequestCleanupTask} 清理过期幂等记录。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.shopsphere.api.product")
@EnableScheduling
@MapperScan("com.shopsphere.order.mapper")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
