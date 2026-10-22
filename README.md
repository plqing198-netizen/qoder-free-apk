# Qoder 白嫖 APK（Qoder-Free）

直连 Qoder 国内版（qoder.com.cn）私有协议的独立 Android 客户端：
OAuth 设备码登录、COSY 签名推理、模型清单拉取、养号（签到/额度/保活）、
本地 OpenAI 兼容网关（默认 9253 端口）。

## 功能

- **登录**：Qoder 国内版 OAuth 设备码（PKCE S256），浏览器授权后轮询取 token
- **聊天**：直连 `gateway.qoder.com.cn` 的 `agent_chat_generation` 接口，
  完整实现 COSY 签名（AES-128-CBC + RSA-1024 + MD5 + 自定义 Base64）
- **模型**：拉取 `/algo/api/v2/model/list`，含 Qwen3.8 / DeepSeek / GLM / Kimi / MiniMax
- **养号**：每日签到、额度查询、token 保活
- **本地网关**：OpenAI 兼容 `/v1/chat/completions` + `/v1/models`，
  默认端口 9253，供 Claude Code / 其他工具直连

## 目录结构

```
src/com/qoder/app/      Java 源码
  Crypto.java           COSY 加密原语（自定义Base64/AES/RSA/MD5）
  CosySession.java      COSY 签名会话
  DeviceFingerprint.java 设备指纹稳定派生
  QoderApi.java         登录/推理/养号 API
  ChatBody.java         agent_chat_generation 请求体构造
  SseParser.java        上游 SSE 信封解包
  HttpUtil.java         HTTP 客户端
  ModelCatalog.java     模型目录解析
  Store.java            账号凭据持久化
  Bridge.java           JS↔Java 桥
  GatewayService.java   本地 OpenAI 网关
  MainActivity.java     WebView 主界面
assets/index.html       前端界面
AndroidManifest.xml     清单
build.sh                一键构建脚本
```

## 构建（Linux arm64）

前置：JDK 21、Android build-tools（aapt2/apksigner/zipalign）、
platform-33 android.jar、d8（Java 版跨平台）。

```bash
./build.sh
# 产物：build/qoder-app.apk
```

## 协议说明

COSY 签名协议逆向自 Qoder 官方客户端，详见 `Crypto.java` / `CosySession.java` 注释。
仅供学习研究。

## 免责声明

本工具为学习研究用途，使用 Qoder 服务请遵守其服务条款。
