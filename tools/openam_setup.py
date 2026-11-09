#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OpenAM OIDC SSO 演示环境自动配置脚本。

功能：
1. 以 amadmin 登录 OpenAM，获取管理员会话 token；
2. 确保 OAuth2 Provider（oauth-oidc 服务）配置正确并持久化；
3. 创建/更新 3 个 OAuth2 客户端（client-1、client-2、resource-server）；
4. 创建演示用户 alice / bob（若不存在）。

重要说明（踩坑记录）：
OpenAM 16.1.4 的 Agent 服务把列表属性存储在 sunKeyValue 中时，必须使用
`[i]=值` 的索引前缀格式（例如 `[0]=http://localhost:8091/cb`）。
如果通过 REST 直接传普通数组，会存成 `属性名=值`，虽然读配置时显示正常，
但运行时 OpenAM 读取时会因缺少 `[i]=` 前缀而得到空集合，导致
redirect_uri_mismatch / unsupported_response_type 等错误。
因此本脚本对所有 list 类型属性统一使用带索引前缀的编码。
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = os.environ.get("OPENAM_BASE_URL", "http://localhost:8090/openam")
ADMIN_USER = os.environ.get("OPENAM_ADMIN_USER", "amadmin")
ADMIN_PASSWORD = os.environ.get("OPENAM_ADMIN_PASSWORD", "Passw0rd!")
REALM = os.environ.get("OPENAM_REALM", "/")

# 演示应用地址
APP1_BASE = os.environ.get("APP1_BASE", "http://localhost:8091")
APP2_BASE = os.environ.get("APP2_BASE", "http://localhost:8092")

# 客户端凭据（需与 Spring 配置保持一致）
CLIENT1_ID = "openam-client-1"
CLIENT1_SECRET = "openam-secret-1"
CLIENT2_ID = "openam-client-2"
CLIENT2_SECRET = "openam-secret-2"
RS_CLIENT_ID = "openam-resource-server"
RS_CLIENT_SECRET = "openam-rs-secret"

DEMO_USERS = [
    {"username": "alice", "password": "Passw0rd!", "email": "alice@example.com", "cn": "Alice"},
    {"username": "bob", "password": "Passw0rd!", "email": "bob@example.com", "cn": "Bob"},
]


def list_attr(values):
    """把普通字符串列表编码为 OpenAM Agent 所需的 `[i]=值` 格式。

    :param values: 原始字符串列表
    :return: 带索引前缀的字符串列表
    """
    return ["[{}]={}".format(i, v) for i, v in enumerate(values)]


def http_json(method, path, token=None, payload=None):
    """发送 JSON 请求并返回解析后的结果。

    :param method: HTTP 方法
    :param path: 相对 BASE_URL 的路径
    :param token: 可选的管理员会话 token
    :param payload: 可选请求体（会被序列化为 JSON）
    :return: 解析后的 JSON（无响应体时返回 None）
    """
    url = BASE_URL + path
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["iPlanetDirectoryPro"] = token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body.strip() else None
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise RuntimeError("{} {} 失败: HTTP {} {}".format(method, path, e.code, body[:400]))


def login():
    """使用 amadmin 登录并返回会话 token。

    :return: iPlanetDirectoryPro token 字符串
    """
    # 认证接口通过请求头传用户名密码，需要单独构造请求
    url = BASE_URL + "/json/realms/root/authenticate"
    req = urllib.request.Request(
        url,
        data=b"{}",
        headers={
            "Content-Type": "application/json",
            "X-OpenAM-Username": ADMIN_USER,
            "X-OpenAM-Password": ADMIN_PASSWORD,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    token = body.get("tokenId")
    if not token:
        raise RuntimeError("管理员登录失败: {}".format(body))
    return token


def ensure_provider(token):
    """确保 OAuth2 Provider 配置正确并已持久化。

    :param token: 管理员会话 token
    """
    print("[1/3] 配置 OAuth2 Provider ...")
    config = http_json("GET", "/json/realms/root/realm-config/services/oauth-oidc", token=token)
    config.pop("_rev", None)
    config.pop("_type", None)

    adv = config.setdefault("advancedOAuth2Config", {})
    adv["supportedScopes"] = [
        "openid|",
        "profile|Your personal information",
        "email|Your email address",
        "message.read|Read messages",
        "message.write|Write messages",
    ]
    adv["defaultScopes"] = ["openid", "profile"]
    adv["codeVerifierEnforced"] = True
    adv["clientsCanSkipConsent"] = True
    # 使用 RS256 签发 JWT 格式的无状态 access token：
    # OpenAM 默认的 introspection 仅允许令牌所属客户端查看，独立资源服务器
    # 无法通过 introspection 校验其他客户端签发的令牌，因此改用 JWT 本地验签。
    adv["tokenSigningAlgorithm"] = "RS256"
    adv["keypairName"] = "test"

    core = config.setdefault("coreOAuth2Config", {})
    core["issueRefreshToken"] = True
    core["issueRefreshTokenOnRefreshedToken"] = True
    core["statelessTokensEnabled"] = True

    oidc = config.setdefault("coreOIDCConfig", {})
    oidc["supportedIDTokenSigningAlgorithms"] = ["RS256"]

    config.setdefault("advancedOIDCConfig", {})["alwaysAddClaimsToToken"] = True
    config["oidcSsoProviderEnabled"] = True

    http_json("PUT", "/json/realms/root/realm-config/services/oauth-oidc", token=token, payload=config)

    # 回读校验，确认配置已真正持久化（而不是每次返回默认值）
    check = http_json("GET", "/json/realms/root/realm-config/services/oauth-oidc", token=token)
    if not check.get("oidcSsoProviderEnabled"):
        raise RuntimeError("OAuth2 Provider 配置未持久化")
    print("      supportedScopes = {}".format(check["advancedOAuth2Config"]["supportedScopes"]))


def upsert_client(token, client_id, attrs):
    """通过 REST 创建/更新 OAuth2 客户端。

    :param token: 管理员会话 token
    :param client_id: 客户端 ID（同时作为 agent 名称）
    :param attrs: 客户端属性字典（list 属性需已用 list_attr 编码）
    :return: 更新后的客户端配置
    """
    print("      配置客户端 {}".format(client_id))
    path = "/json/realms/root/realm-config/agents/OAuth2Client/{}".format(client_id)
    return http_json("PUT", path, token=token, payload=attrs)


def build_client_attrs(client_id, secret, redirect_base, post_logout, is_rs=False):
    """构造客户端属性字典。

    :param client_id: 客户端 ID
    :param secret: 客户端密钥
    :param redirect_base: 回调地址前缀（如 http://localhost:8091）
    :param post_logout: 登出后跳转地址
    :param is_rs: 是否为资源服务器客户端（无需回调地址）
    :return: 属性字典
    """
    scopes = ["openid", "profile", "email", "message.read", "message.write"]
    attrs = {
        "com.forgerock.openam.oauth2provider.clientType": "Confidential",
        "com.forgerock.openam.oauth2provider.tokenEndPointAuthMethod": "client_secret_basic",
        "com.forgerock.openam.oauth2provider.idTokenSignedResponseAlg": "RS256",
        "com.forgerock.openam.oauth2provider.subjectType": "Public",
        "com.forgerock.openam.oauth2provider.scopes": list_attr(scopes),
        "com.forgerock.openam.oauth2provider.defaultScopes": list_attr(["openid", "profile"]),
        "com.forgerock.openam.oauth2provider.claims": list_attr(
            ["sub", "name", "preferred_username", "given_name", "family_name", "email"]
        ),
        "com.forgerock.openam.oauth2provider.responseTypes": list_attr(["code"]),
        "com.forgerock.openam.oauth2provider.grantTypes": list_attr(
            ["authorization_code", "refresh_token"]
        ),
        "com.forgerock.openam.oauth2provider.clientName": list_attr([client_id]),
        "isConsentImplied": True,
        "com.forgerock.openam.oauth2provider.accessTokenLifeTime": 3600,
        "com.forgerock.openam.oauth2provider.refreshTokenLifeTime": 604800,
        "com.forgerock.openam.oauth2provider.authorizationCodeLifeTime": 120,
        "sunIdentityServerDeviceStatus": "Active",
    }
    if not is_rs:
        redirect_uri = "{}/login/oauth2/code/{}".format(redirect_base, client_id)
        attrs["com.forgerock.openam.oauth2provider.redirectionURIs"] = list_attr([redirect_uri])
        attrs["com.forgerock.openam.oauth2provider.postLogoutRedirectURI"] = list_attr(
            ["{}/".format(redirect_base)]
        )
    # 设置/更新客户端密钥
    attrs["userpassword"] = secret
    return attrs


def ensure_clients(token):
    """创建/更新全部演示客户端。

    :param token: 管理员会话 token
    """
    print("[2/3] 配置 OAuth2 客户端 ...")
    upsert_client(
        token,
        CLIENT1_ID,
        build_client_attrs(CLIENT1_ID, CLIENT1_SECRET, APP1_BASE, APP1_BASE),
    )
    upsert_client(
        token,
        CLIENT2_ID,
        build_client_attrs(CLIENT2_ID, CLIENT2_SECRET, APP2_BASE, APP2_BASE),
    )
    upsert_client(
        token,
        RS_CLIENT_ID,
        build_client_attrs(RS_CLIENT_ID, RS_CLIENT_SECRET, "", "", is_rs=True),
    )


def ensure_user(token, username, password, email, cn):
    """创建演示用户（若已存在则跳过）。

    :param token: 管理员会话 token
    :param username: 用户名
    :param password: 密码
    :param email: 邮箱
    :param cn: 显示名
    """
    path = "/json/realms/root/users/{}".format(urllib.parse.quote(username))
    try:
        http_json("GET", path, token=token)
        print("      用户 {} 已存在，跳过".format(username))
        return
    except RuntimeError:
        pass
    payload = {
        "username": username,
        "userpassword": password,
        "mail": email,
        "cn": cn,
        "sn": cn,
        "givenName": cn,
    }
    http_json("POST", "/json/realms/root/users?_action=create", token=token, payload=payload)
    print("      用户 {} 已创建".format(username))


def main():
    """脚本入口：依次完成 Provider、客户端、用户配置。"""
    print("OpenAM 地址: {}".format(BASE_URL))
    token = login()
    print("管理员登录成功")
    ensure_provider(token)
    ensure_clients(token)
    print("[3/3] 配置演示用户 ...")
    for user in DEMO_USERS:
        ensure_user(token, user["username"], user["password"], user["email"], user["cn"])
    print("配置完成。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print("配置失败: {}".format(exc), file=sys.stderr)
        sys.exit(1)
