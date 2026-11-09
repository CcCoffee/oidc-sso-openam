package com.example.openam.resource.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 资源服务器安全配置。
 *
 * <p>OpenAM 在 {@code statelessTokensEnabled=true} 模式下签发 RS256 签名的 JWT Access Token，
 * 资源服务器使用 OpenAM 公布的 JWK Set 在本地校验签名与有效期，
 * 并把 JWT 中的 {@code scope} 声明自动映射为 {@code SCOPE_} 前缀的权限。</p>
 */
@Configuration
@EnableWebSecurity
public class ResourceServerSecurityConfig {

    /**
     * 配置资源服务器的认证与授权规则。
     *
     * @param http Spring Security 的 HTTP 安全构建器
     * @return 资源服务器过滤器链
     * @throws Exception 构建失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/api/me").hasAuthority("SCOPE_profile")
                .requestMatchers(HttpMethod.GET, "/api/messages").hasAuthority("SCOPE_message.read")
                .requestMatchers(HttpMethod.POST, "/api/messages").hasAuthority("SCOPE_message.write")
                .anyRequest().authenticated());
        http.oauth2ResourceServer((oauth2) -> oauth2
                .jwt(Customizer.withDefaults()));
        return http.build();
    }
}
