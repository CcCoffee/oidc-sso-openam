package com.example.openam.client.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * 客户端（RP）安全配置。
 *
 * <p>把浏览器重定向到 OpenAM 的授权端点，接收授权码并换取 Token，
 * 再用 ID Token / UserInfo 建立本地会话。</p>
 */
@Configuration
@EnableWebSecurity
public class ClientSecurityConfig {

    /**
     * 配置客户端的登录与登出行为。
     *
     * <ul>
     *     <li>oauth2Login：启用授权码流程；</li>
     *     <li>PKCE：通过 OAuth2AuthorizationRequestCustomizers.withPkce() 在授权请求中加入
     *         code_challenge，Spring Security 会在换取 Token 时自动附带 code_verifier；</li>
     *     <li>登出：使用 OidcClientInitiatedLogoutSuccessHandler 跳转到 OpenAM 的
     *         end_session_endpoint，实现 RP-Initiated Logout。</li>
     * </ul>
     *
     * @param http                        Spring Security 的 HTTP 安全构建器
     * @param clientRegistrationRepository 客户端注册信息仓库
     * @return 客户端过滤器链
     * @throws Exception 构建失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        // 默认解析器负责生成 state、nonce；这里额外挂上 PKCE 定制器。
        DefaultOAuth2AuthorizationRequestResolver authorizationRequestResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository, "/oauth2/authorization");
        authorizationRequestResolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());

        http.authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/", "/error").permitAll()
                .anyRequest().authenticated());
        http.oauth2Login((oauth2Login) -> oauth2Login
                .authorizationEndpoint((endpoint) -> endpoint
                        .authorizationRequestResolver(authorizationRequestResolver))
                .defaultSuccessUrl("/profile", true));
        http.logout((logout) -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        return http.build();
    }

    /**
     * 构建 OIDC RP-Initiated Logout 处理器。
     *
     * <p>登出时浏览器会被导向 OpenAM 的 end_session_endpoint，并携带 id_token_hint 与
     * post_logout_redirect_uri；OpenAM 清理自己的会话后再跳回客户端首页。</p>
     *
     * @param clientRegistrationRepository 客户端注册信息仓库
     * @return 登出成功处理器
     */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        // {baseUrl} 会展开为 http://localhost:8091 或 :8092，
        // 必须与 OpenAM 客户端配置中的 Post Logout Redirect URIs 一致。
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");
        return logoutSuccessHandler;
    }
}
