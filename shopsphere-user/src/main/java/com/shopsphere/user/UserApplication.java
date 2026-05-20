package com.shopsphere.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * User 服务入口（端口 8081）。
 * <p>对齐 docs/api-contracts.md §5：服务注册名 shopsphere-user，库 shopsphere_user。
 *
 * <p><b>不开 {@code @EnableFeignClients}</b>：本服务不消费下游 Feign（T1.3 范围内）；
 * user-api 在 classpath 仅用于 {@code InternalUserController implements UserFeignClient}
 * 的编译期契约对齐，无需 Feign 代理基础设施。后续服务下沉时再开 {@code basePackages="com.shopsphere.api"}。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.shopsphere.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
