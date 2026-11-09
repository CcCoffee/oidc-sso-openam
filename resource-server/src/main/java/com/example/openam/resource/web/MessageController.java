package com.example.openam.resource.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受保护的资源 API。
 *
 * <p>这些接口只能通过携带有效 JWT Access Token 访问，scope 校验由
 * {@link com.example.openam.resource.config.ResourceServerSecurityConfig} 完成。</p>
 */
@RestController
@RequestMapping("/api")
public class MessageController {

    /** 演示用的内存消息列表。 */
    private final List<Map<String, Object>> messages = new ArrayList<>(List.of(
            Map.of("id", 1, "content", "这条消息需要 message.read scope。", "createdBy", "system")
    ));

    /**
     * 返回当前 Access Token 的身份信息与全部声明。
     *
     * <p>需要 scope：profile。</p>
     *
     * @param jwt 资源服务器验签后的 JWT 令牌
     * @return 主体、scope、clientId 与全部 claims
     */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", jwt.getSubject());
        result.put("scope", jwt.getClaimAsString("scope"));
        result.put("clientId", jwt.getAudience());
        result.put("issuer", jwt.getClaimAsString("iss"));
        result.put("claims", jwt.getClaims());
        return result;
    }

    /**
     * 读取消息列表。
     *
     * <p>需要 scope：message.read。</p>
     *
     * @return 消息列表
     */
    @GetMapping("/messages")
    public List<Map<String, Object>> messages() {
        return messages;
    }

    /**
     * 新增一条消息。
     *
     * <p>需要 scope：message.write。</p>
     *
     * @param body 请求体，必须包含 content 字段
     * @param jwt  当前 Access Token 的主体
     * @return 新建的消息
     */
    @PostMapping("/messages")
    public Map<String, Object> createMessage(@RequestBody Map<String, String> body,
                                             @AuthenticationPrincipal Jwt jwt) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be empty");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        synchronized (messages) {
            message.put("id", messages.size() + 1);
            message.put("content", content);
            message.put("createdBy", jwt.getSubject());
            message.put("createdAt", Instant.now().toString());
            messages.add(message);
        }
        return message;
    }
}
