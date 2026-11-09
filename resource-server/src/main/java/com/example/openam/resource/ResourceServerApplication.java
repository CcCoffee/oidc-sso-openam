package com.example.openam.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 资源服务器启动类。
 *
 * <p>只接受携带有效 OpenAM Access Token 的请求，并按 scope 授权。</p>
 */
@SpringBootApplication
public class ResourceServerApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ResourceServerApplication.class, args);
    }
}
