package com.example.openam.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OIDC 客户端（Relying Party）启动类。
 *
 * <p>同一份代码通过 Spring Profile 启动两个实例，用于演示单点登录：</p>
 * <ul>
 *     <li>app1：端口 8091，使用 OpenAM 客户端 openam-client-1；</li>
 *     <li>app2：端口 8092，使用 OpenAM 客户端 openam-client-2。</li>
 * </ul>
 */
@SpringBootApplication
public class ClientApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ClientApplication.class, args);
    }
}
