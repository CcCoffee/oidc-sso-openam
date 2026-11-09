package com.example.openam.client.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 客户端页面控制器。
 *
 * <p>演示内容：首页、登录后的用户信息页（ID Token claims / Access Token / UserInfo）、
 * 以及携带 Access Token 调用资源服务器 API 的页面。</p>
 */
@Controller
public class ProfileController {

    /** 资源服务器地址。 */
    private static final String RESOURCE_SERVER = "http://localhost:8093";

    private final RestClient restClient = RestClient.create();

    /** 当前实例在 OpenAM 注册的客户端标识（openam-client-1 或 openam-client-2）。 */
    private final String registrationId;

    /** 当前实例端口，用于页面展示以区分两个应用。 */
    private final String port;

    /** OpenAM 部署根地址，例如 http://localhost:8090/openam。 */
    private final String openamBaseUrl;

    /**
     * 构造函数：从配置读取客户端标识、端口与 OpenAM 地址。
     *
     * @param registrationId 客户端注册标识
     * @param port           当前服务端口
     * @param openamBaseUrl  OpenAM 根地址
     */
    public ProfileController(@Value("${demo.registration-id}") String registrationId,
                             @Value("${server.port}") String port,
                             @Value("${demo.openam-base-url}") String openamBaseUrl) {
        this.registrationId = registrationId;
        this.port = port;
        this.openamBaseUrl = openamBaseUrl;
    }

    /**
     * 首页：未登录时显示登录入口，已登录时显示进入资料页的入口。
     *
     * @param oidcUser 当前登录用户，未登录时为 null
     * @return HTML 页面
     */
    @GetMapping("/")
    public ResponseEntity<String> home(@AuthenticationPrincipal OidcUser oidcUser) {
        String content;
        if (oidcUser == null) {
            content = """
                    <p class="muted">当前未登录。</p>
                    <p><a class="btn" href="/oauth2/authorization/%s">使用 OpenAM 登录</a></p>
                    <p class="muted">点击后会跳转到 OpenAM（http://localhost:8090/openam）的登录页。
                    如果本浏览器已经登录过另一个应用，OpenAM 会直接签发授权码，不会再要求输入密码。</p>
                    <p class="muted">OpenAM 的 discovery 文档：</p>
                    <p><a class="btn secondary" href="%s/oauth2/.well-known/openid-configuration"
                    target="_blank" rel="noopener">查看 OpenID Provider 元数据</a></p>
                    """.formatted(registrationId, openamBaseUrl);
        } else {
            content = """
                    <p>当前登录用户：<b>%s</b></p>
                    <p><a class="btn" href="/profile">查看 ID Token / Access Token / UserInfo</a></p>
                    """.formatted(HtmlUtils.htmlEscape(oidcUser.getName()));
        }
        return html("OpenAM OIDC SSO Demo Client", content);
    }

    /**
     * 用户信息页：展示 OIDC 登录后获得的令牌内容。
     *
     * <p>ID Token 用于证明“用户是谁”，Access Token 用于访问资源服务器。
     * 页面还提供登出按钮，触发 RP-Initiated Logout。</p>
     *
     * @param oidcUser         当前 OIDC 用户
     * @param authorizedClient 当前客户端持有的授权信息
     * @param request          HTTP 请求，用于读取 CSRF Token 渲染登出表单
     * @return HTML 页面
     */
    @GetMapping("/profile")
    public ResponseEntity<String> profile(
            @AuthenticationPrincipal OidcUser oidcUser,
            @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient authorizedClient,
            HttpServletRequest request) {

        StringBuilder claims = new StringBuilder();
        for (Map.Entry<String, Object> entry : oidcUser.getIdToken().getClaims().entrySet()) {
            claims.append("<tr><td>%s</td><td><code>%s</code></td></tr>".formatted(
                    HtmlUtils.htmlEscape(entry.getKey()),
                    escapeValue(entry.getValue())));
        }

        String userInfo;
        try {
            userInfo = restClient.get()
                    .uri(openamBaseUrl + "/oauth2/userinfo")
                    .header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + authorizedClient.getAccessToken().getTokenValue())
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            userInfo = "UserInfo 调用失败：" + ex.getMessage();
        }

        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        String refreshToken = authorizedClient.getRefreshToken() == null
                ? "（无，或未被客户端保存）"
                : authorizedClient.getRefreshToken().getTokenValue();

        // CSRF Token 由 Spring Security 延迟生成，这里主动读取以便放入登出表单。
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String csrfField = csrfToken == null ? ""
                : "<input type=\"hidden\" name=\"_csrf\" value=\"%s\"/>"
                        .formatted(HtmlUtils.htmlEscape(csrfToken.getToken()));

        String content = """
                <h2>ID Token（OIDC 身份令牌）</h2>
                <p class="muted">由 OpenAM 签发。客户端已校验签名与 issuer，确认用户身份。</p>
                <table>%s</table>

                <h2>UserInfo 端点返回</h2>
                <p class="muted">客户端携带 Access Token 调用 OpenAM 的 /oauth2/userinfo。</p>
                <pre>%s</pre>

                <h2>Access Token</h2>
                <p class="muted">发送给资源服务器时放在 Authorization: Bearer 头中。</p>
                <pre>%s</pre>

                <h2>Refresh Token</h2>
                <pre>%s</pre>

                <h2>受保护资源</h2>
                <p><a class="btn" href="/call-resource">调用资源服务器 /api/me 和 /api/messages</a></p>

                <h2>单点登录从哪里来</h2>
                <p class="muted">OpenAM 在浏览器中保存 <code>iPlanetDirectoryPro</code> 会话 Cookie。
                两个应用（8091 与 8092）都跳转到同一个 OpenAM，因此共享同一个登录会话。
                在第二个应用点击登录时，OpenAM 直接签发授权码，不会再出现登录页。</p>
                <p><a class="btn secondary" href="%s/oauth2/.well-known/openid-configuration"
                target="_blank" rel="noopener">查看 OpenAM OpenID Provider 元数据</a></p>

                <h2>登出</h2>
                <p class="muted">RP-Initiated Logout：清理本地会话后跳转到 OpenAM 的
                end_session_endpoint。</p>
                <form method="post" action="/logout">%s<button type="submit">登出</button></form>
                """.formatted(claims, HtmlUtils.htmlEscape(userInfo),
                HtmlUtils.htmlEscape(accessToken), HtmlUtils.htmlEscape(refreshToken),
                openamBaseUrl, csrfField);

        return html("OIDC 用户信息", content);
    }

    /**
     * 使用当前用户的 Access Token 调用资源服务器。
     *
     * <p>资源服务器使用 OpenAM 公布的 JWK Set 本地校验 JWT Access Token 的签名并检查 scope，
     * 客户端不参与授权判断。</p>
     *
     * @param authorizedClient 当前客户端持有的授权信息
     * @return HTML 页面，展示资源服务器返回的原始 JSON
     */
    @GetMapping("/call-resource")
    public ResponseEntity<String> callResource(
            @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient authorizedClient) {
        String bearer = "Bearer " + authorizedClient.getAccessToken().getTokenValue();

        String me = restClient.get()
                .uri(RESOURCE_SERVER + "/api/me")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .retrieve()
                .body(String.class);
        String messages = restClient.get()
                .uri(RESOURCE_SERVER + "/api/messages")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .retrieve()
                .body(String.class);

        String content = """
                <h2>GET /api/me（需要 scope profile）</h2>
                <pre>%s</pre>
                <h2>GET /api/messages（需要 scope message.read）</h2>
                <pre>%s</pre>
                <p><a href="/profile">返回用户信息页</a></p>
                """.formatted(HtmlUtils.htmlEscape(me), HtmlUtils.htmlEscape(messages));

        return html("资源服务器响应", content);
    }

    /**
     * 把 claim 值转成安全的字符串。
     *
     * @param value claim 原始值
     * @return 转义后的字符串
     */
    private String escapeValue(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(String.valueOf(value));
    }

    /**
     * 生成一个最简单的 HTML 页面（避免为 demo 引入模板引擎）。
     *
     * @param title   页面标题
     * @param content 页面主体 HTML
     * @return HTTP 响应
     */
    private ResponseEntity<String> html(String title, String content) {
        // 标题带上端口，便于在同一浏览器中区分两个 RP 实例。
        String pageTitle = title + "（端口 " + port + "）";
        String page = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>%s</title>
                  <style>
                    body { font-family: -apple-system, "PingFang SC", sans-serif; max-width: 900px; margin: 40px auto; padding: 0 20px; color: #222; }
                    h1 { border-bottom: 2px solid #eee; padding-bottom: 12px; }
                    h2 { margin-top: 28px; font-size: 18px; }
                    table { border-collapse: collapse; width: 100%%; }
                    td { border: 1px solid #e5e5e5; padding: 8px; vertical-align: top; }
                    td:first-child { width: 220px; font-weight: 600; background: #fafafa; }
                    pre { background: #f6f8fa; padding: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-all; }
                    code { word-break: break-all; }
                    .muted { color: #666; }
                    .btn, button { display: inline-block; background: #2563eb; color: #fff; padding: 8px 14px; border: 0; border-radius: 4px; text-decoration: none; cursor: pointer; margin-right: 8px; }
                    .btn.secondary { background: #475569; }
                  </style>
                </head>
                <body>
                  <h1>%s</h1>
                  %s
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(pageTitle), HtmlUtils.htmlEscape(pageTitle), content);
        // 明确声明 UTF-8，避免浏览器按 ISO-8859-1 解码导致中文乱码。
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(page);
    }
}
