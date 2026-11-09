#!/usr/bin/env bash
# 端到端验证 OpenAM OIDC SSO demo 的核心链路。
# 依赖：OpenAM（8090）与三个 Spring Boot 服务（8091/8092/8093）已启动，
# 且已执行过 tools/openam_setup.py。
set -euo pipefail

python3 - <<'PY'
import base64
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

OPENAM = "http://localhost:8090/openam"
APP1 = "http://localhost:8091"
RS = "http://localhost:8093"
CLIENT_ID = "openam-client-1"
CLIENT_SECRET = "openam-secret-1"
REDIRECT_URI = APP1 + "/login/oauth2/code/openam-client-1"
USER = "alice"
PASSWORD = "Passw0rd!"

failures = []


def check(name, ok, detail=""):
    """记录并打印一条检查结果。"""
    print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))
    if not ok:
        failures.append(name)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止自动跟随 302，便于读取 Location。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def request(method, url, data=None, headers=None):
    """发送请求，返回 (状态码, 响应头字典, 响应体文本)。"""
    body = None
    hdrs = dict(headers or {})
    if data is not None:
        if isinstance(data, dict):
            body = urllib.parse.urlencode(data).encode()
            hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
        else:
            body = data
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        resp = opener.open(req, timeout=30)
        return resp.status, dict(resp.headers), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")


def location(headers):
    """读取响应头中的 Location。"""
    for k, v in headers.items():
        if k.lower() == "location":
            return v
    return ""


def query_param(url, name):
    """从 URL 查询串中提取参数。"""
    q = urllib.parse.urlparse(url).query
    return urllib.parse.parse_qs(q).get(name, [None])[0]


# 1. discovery
status, _, body = request("GET", OPENAM + "/oauth2/.well-known/openid-configuration")
meta = json.loads(body) if status == 200 else {}
check("discovery 可用", status == 200)
check("issuer 正确", meta.get("issuer") == OPENAM + "/oauth2", str(meta.get("issuer")))
check("支持 RS256", "RS256" in meta.get("id_token_signing_alg_values_supported", []))
check("存在 end_session_endpoint", bool(meta.get("end_session_endpoint")))

# 2. 用户登录（建立 OpenAM SSO 会话）
status, _, body = request(
    "POST",
    OPENAM + "/json/realms/root/authenticate",
    data=b"{}",
    headers={
        "Content-Type": "application/json",
        "X-OpenAM-Username": USER,
        "X-OpenAM-Password": PASSWORD,
    },
)
sso = json.loads(body).get("tokenId") if status == 200 else None
check("用户 %s 登录" % USER, bool(sso))

# 3. PKCE 授权码流程
verifier = "0123456789abcdef0123456789abcdef"
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
params = {
    "client_id": CLIENT_ID,
    "response_type": "code",
    "scope": "openid profile email message.read message.write",
    "redirect_uri": REDIRECT_URI,
    "state": "verify-state",
    "nonce": "verify-nonce",
    "code_challenge": challenge,
    "code_challenge_method": "S256",
}
status, headers, _ = request(
    "GET",
    OPENAM + "/oauth2/authorize?" + urllib.parse.urlencode(params),
    headers={"Cookie": "iPlanetDirectoryPro=" + sso},
)
authorize_location = location(headers)
code = query_param(authorize_location, "code")
check("授权端点返回授权码", status == 302 and bool(code), authorize_location[:80])

# 4. 换取 Token（client_secret_basic）
basic = base64.b64encode(("%s:%s" % (CLIENT_ID, CLIENT_SECRET)).encode()).decode()
status, _, body = request(
    "POST",
    OPENAM + "/oauth2/access_token",
    data={
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": REDIRECT_URI,
        "code_verifier": verifier,
    },
    headers={"Authorization": "Basic " + basic},
)
tokens = json.loads(body) if status == 200 else {}
access_token = tokens.get("access_token", "")
check("换取 access_token", bool(access_token))
check("换取 id_token", bool(tokens.get("id_token")))
check("换取 refresh_token", bool(tokens.get("refresh_token")))


def jwt_claims(token):
    """解析 JWT 的 payload（不验签，仅用于展示与断言）。"""
    if token.count(".") != 2:
        return {}
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


claims = jwt_claims(access_token)
check("access_token 为 JWT", bool(claims))
check("JWT sub 为 " + USER, claims.get("sub") == USER, str(claims.get("sub")))
check("JWT 包含 scope", "message.read" in str(claims.get("scope")))

# 5. UserInfo
status, _, body = request(
    "GET", OPENAM + "/oauth2/userinfo", headers={"Authorization": "Bearer " + access_token}
)
userinfo = json.loads(body) if status == 200 else {}
check("userinfo 返回 sub", userinfo.get("sub") == USER, str(userinfo.get("sub")))

# 6. 资源服务器（JWT 本地验签 + scope 校验）
status, _, body = request(
    "GET", RS + "/api/me", headers={"Authorization": "Bearer " + access_token}
)
check("资源服务器 /api/me", status == 200, "HTTP %s" % status)
status, _, _ = request("GET", RS + "/api/messages", headers={"Authorization": "Bearer " + access_token})
check("资源服务器 /api/messages", status == 200, "HTTP %s" % status)
status, _, _ = request("GET", RS + "/api/messages")
check("资源服务器未授权返回 401", status == 401, "HTTP %s" % status)

# 7. SSO：同一会话再次授权（prompt=none）应直接返回授权码
sso_params = dict(params)
sso_params["prompt"] = "none"
status, headers, _ = request(
    "GET",
    OPENAM + "/oauth2/authorize?" + urllib.parse.urlencode(sso_params),
    headers={"Cookie": "iPlanetDirectoryPro=" + sso},
)
check("SSO 会话可直接授权", status == 302 and bool(query_param(location(headers), "code")))

# 8. RP-Initiated Logout：end_session 依据 id_token_hint 重定向回客户端
status, headers, _ = request(
    "GET",
    OPENAM + "/oauth2/connect/endSession?" + urllib.parse.urlencode(
        {"id_token_hint": tokens.get("id_token", ""), "post_logout_redirect_uri": APP1 + "/"}
    ),
    headers={"Cookie": "iPlanetDirectoryPro=" + sso},
)
check("end_session 重定向回客户端", status == 302 and location(headers).startswith(APP1), location(headers)[:80])

# 9. 彻底登出：OpenAM 16.1.4 的 end_session 不会销毁 SSO 会话，
#    需要显式调用会话注销接口（浏览器侧可通过 /openam/UI/Logout 触发）。
status, _, body = request(
    "POST",
    OPENAM + "/json/realms/root/sessions?_action=logout",
    data=b"{}",
    headers={"Content-Type": "application/json", "iPlanetDirectoryPro": sso},
)
check("调用会话注销接口", status == 200, body[:60])

# 10. 注销后 SSO 会话应失效
status, headers, _ = request(
    "GET",
    OPENAM + "/oauth2/authorize?" + urllib.parse.urlencode(sso_params),
    headers={"Cookie": "iPlanetDirectoryPro=" + sso},
)
check(
    "登出后 SSO 会话失效",
    query_param(location(headers), "error") == "interaction_required",
    location(headers)[:100],
)

print()
if failures:
    print("失败项：%s" % ", ".join(failures))
    sys.exit(1)
print("全部检查通过。")
PY
