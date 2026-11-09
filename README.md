# OpenAM OIDC SSO Demo

使用 [OpenIdentityPlatform OpenAM 16.1.4](https://github.com/OpenIdentityPlatform/OpenAM/releases) 作为
OpenID Provider（OP），演示基于 **OIDC 授权码 + PKCE** 的单点登录（SSO）。

- 参考项目：`/Users/kevin/dev/oidc-sso`（Spring Authorization Server 版）
- 本项目把授权服务器替换为 **OpenAM**，其余客户端/资源服务器结构保持一致

## 架构

```
                    ┌──────────────────────────────┐
                    │  OpenAM 16.1.4 (Tomcat 11)   │
   浏览器  ────────▶│  http://localhost:8090/openam │
   (共享 SSO Cookie)│  OP：授权码 + PKCE + JWT       │
                    └──────────────┬───────────────┘
                                   │ /oauth2/authorize、/oauth2/access_token
                 ┌─────────────────┴─────────────────┐
                 ▼                                   ▼
   ┌───────────────────────────┐       ┌───────────────────────────┐
   │ client-app（app1）8091    │       │ client-app（app2）8092    │
   │ openam-client-1           │       │ openam-client-2           │
   └─────────────┬─────────────┘       └─────────────┬─────────────┘
                 │ Bearer JWT Access Token           │
                 └─────────────────┬─────────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │ resource-server 8093         │
                    │ 本地 JWKS 验签 + scope 校验   │
                    └──────────────────────────────┘
```

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| OpenAM | 8090 | OIDC Provider（`/openam`） |
| client-app app1 | 8091 | 注册为 `openam-client-1` |
| client-app app2 | 8092 | 注册为 `openam-client-2` |
| resource-server | 8093 | 受保护 API，校验 JWT Access Token |

## 目录结构

```
.
├── pom.xml                     # 父 POM（Spring Boot 4.1.2 / Java 21）
├── client-app/                 # OIDC 客户端（app1 / app2 两个 Profile）
├── resource-server/            # JWT 资源服务器
├── tools/
│   ├── openam_setup.py         # OpenAM 自动配置（Provider、客户端、用户）
│   ├── start-openam.sh         # 启动 Tomcat + OpenAM
│   ├── stop-openam.sh          # 停止 OpenAM
│   ├── start-apps.sh           # 构建并启动三个 Spring Boot 服务
│   ├── stop-apps.sh            # 停止三个 Spring Boot 服务
│   └── verify.sh               # 端到端验证脚本
└── .runtime/                   # 本地运行时（Tomcat、OpenAM 配置、日志，非源码）
```

## 前置条件

- macOS
- JDK 21+
- Maven 3.9+
- 已按下方步骤准备 `.runtime/` 下的 Tomcat 与 OpenAM

## 准备 OpenAM 运行时（首次）

本项目不依赖 Docker，直接在本地 Tomcat 中部署 OpenAM WAR：

1. 下载 Tomcat 11 并解压到 `.runtime/tomcat`，把 `conf/server.xml` 的 HTTP 端口改为 `8090`。
2. 下载 `OpenAM-16.1.4.war`，放到 `.runtime/tomcat/webapps/openam.war`。
3. 下载 `SSOConfiguratorTools-16.1.4.zip` 并解压到 `.runtime/configtools`。
4. 使用 `.runtime/openam.conf` 执行配置器（关键项）：

```properties
SERVER_URL=http://localhost:8090
DEPLOYMENT_URI=/openam
BASE_DIR=/Users/kevin/dev/openam-oidc-sso/.runtime/openam-config
ADMIN_PWD=Passw0rd!
AMLDAPUSERPASSWD=AgentPassw0rd!   # 不能与管理员密码相同
COOKIE_DOMAIN=localhost
DATA_STORE=embedded
ROOT_SUFFIX=dc=openam,dc=example,dc=org
```

```bash
cd .runtime/configtools
./configurator.sh -f ../openam.conf
```

## 启动

```bash
# 1. 启动 OpenAM（首次启动较慢，脚本会等待 discovery 就绪）
tools/start-openam.sh

# 2. 配置 OAuth2 Provider、三个客户端、演示用户（幂等，可重复执行）
python3 tools/openam_setup.py

# 3. 构建并启动三个 Spring Boot 服务
tools/start-apps.sh

# 4. 端到端验证
tools/verify.sh
```

停止：

```bash
tools/stop-apps.sh
tools/stop-openam.sh
```

## 演示

1. 打开 <http://localhost:8091/>，点击「使用 OpenAM 登录」。
2. 使用演示账号登录：`alice / Passw0rd!`（或 `bob / Passw0rd!`）。
3. 登录后进入 `/profile`，可查看：
   - ID Token 的 Claims
   - OpenAM `/oauth2/userinfo` 返回的用户信息
   - Access Token / Refresh Token
4. 新开标签访问 <http://localhost:8092/>，登录时**无需再次输入密码**（共享 OpenAM SSO 会话）。
5. 点击「调用资源服务器」，使用当前 Access Token 访问 8093 的 `/api/me`、`/api/messages`。

## 实现要点

- **授权码 + PKCE**：`ClientSecurityConfig` 通过
  `OAuth2AuthorizationRequestCustomizers.withPkce()` 开启 PKCE；OpenAM 侧
  `codeVerifierEnforced=true` 强制校验。
- **SSO**：两个客户端通过浏览器共享 OpenAM 的 `iPlanetDirectoryPro` 会话 Cookie，
  第二次登录由 OpenAM 静默完成。
- **JWT Access Token**：Provider 开启 `statelessTokensEnabled=true`，用 RS256 签发
  JWT 格式 Access Token；资源服务器通过 `issuer-uri` 拉取 JWK Set 本地验签，
  并把 `scope` 声明映射为 `SCOPE_*` 权限。
- **scope 授权**：`/api/me` 需要 `profile`，`GET /api/messages` 需要 `message.read`，
  `POST /api/messages` 需要 `message.write`。

## 关键踩坑与说明

### 1. OpenAM Agent 列表属性必须使用 `[i]=` 索引前缀

通过 REST 创建/更新 `OAuth2Client` agent 时，list 类型属性（`redirectionURIs`、`scopes`、
`responseTypes`、`grantTypes`、`postLogoutRedirectURI` 等）在 `sunKeyValue` 中必须存成：

```
com.forgerock.openam.oauth2provider.redirectionURIs=[0]=http://localhost:8091/login/oauth2/code/openam-client-1
```

如果直接传普通数组，会存成 `属性名=值`，配置读出来看似正常，但运行时会因为
`Utils.stripAttributeNameFromValue` 找不到 `=` 前缀而得到空集合，表现为：

- `redirect_uri_mismatch`
- `unsupported_response_type`
- 登出地址为空等

`tools/openam_setup.py` 中的 `list_attr()` 已统一处理该格式。

### 2. introspection 只能由令牌所属客户端调用

OpenAM 16.1.4 默认的 `OAuth2TokenIntrospectionHandler` 会校验
`token.getClientId().equals(请求方 clientId)`，独立的资源服务器客户端自省其他客户端签发的
令牌时只会得到 `active=false`。因此本项目改用 **JWT Access Token + 本地 JWKS 验签**。

### 3. `end_session` 不会销毁 SSO 会话

实测 OpenAM 16.1.4 的 `/oauth2/connect/endSession`（带 `id_token_hint` 与
`post_logout_redirect_uri`）会正确重定向回客户端，但**不会销毁 OpenAM 的 SSO 会话**。
RP 本地会话会被清除，但再次点击登录仍会静默通过 SSO。

如需彻底登出，可使用（均经实测有效）：

```bash
# 浏览器访问（会进入 OpenAM 的“已登出”页面）
http://localhost:8090/openam/UI/Logout?goto=http://localhost:8091/

# 或直接调用会话注销接口（需要携带 iPlanetDirectoryPro）
curl -X POST 'http://localhost:8090/openam/json/realms/root/sessions?_action=logout' \
  -H 'Content-Type: application/json' \
  -H 'iPlanetDirectoryPro: <会话 token>' -d '{}'
```

### 4. 与参考项目的差异

| 项目 | `/Users/kevin/dev/oidc-sso` | 本项目 |
| --- | --- | --- |
| 授权服务器 | Spring Authorization Server | OpenAM 16.1.4 |
| 客户端注册 | 配置文件 | OpenAM REST 自动配置 |
| Access Token | JWT | JWT（`statelessTokensEnabled`） |
| 资源服务器校验 | JWKS | OpenAM JWKS |
| 登出 | 标准 OIDC RP-Initiated Logout | 标准 + 上述 SSO 会话注销说明 |

## 演示账号

| 用户名 | 密码 |
| --- | --- |
| alice | `Passw0rd!` |
| bob | `Passw0rd!` |
| amadmin（OpenAM 管理） | `Passw0rd!` |
