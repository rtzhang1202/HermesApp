# HermesApp — 手机端 AI Agent（增强版）

[![Release](https://img.shields.io/badge/Release-v2.0.9-blue.svg)](https://github.com/rtzhang1202/HermesApp/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://www.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **让 AI 真正掌控你的 Android 手机。**

1:1 复刻 [Hermes](https://github.com/nicepkg/hermes) 框架的 Kotlin 版 Agent Loop，嵌入 Android 平台，实现完整 AI Agent 能力：看屏幕、点 App、跑代码、连平台。

本仓库是**深度增强版**（`dev` 分支为增强主线），在上游基础上重点强化了 **语音助手、屏幕识别、系统能力调用、用户记忆** 四大方向，并做了全局品牌视觉统一。

---

## ⭐ 本版重点改进

### 🎙 语音助手（Gemini / 超级小爱风格）
- 全屏语音界面：深紫玻璃态 + 彩条波形 + 气泡对话 + 建议胶囊；支持**外部按键唤醒**（如 KeyPilot 长按 Essential 键）
- **云端 ASR 优先**（429 退避、失败才本地兜底），中英语种锁定
- **采集链路调敏 + 降噪**：自适应 VAD（30s 底噪 p80 分位 ×1.5，下限 80）+ `NoiseSuppressor` 硬降噪 + 上传前软件 AGC —— 弱声不漏字、静音零误触发
- 单发对话模式（说完即停，不自动续听）+ 空回答兜底
- 气泡内**图片真实渲染**（link 标签 / 本地路径 / markdown 三源，点击全屏）

### 🔍 识别屏幕（Screen Lens）
- 语音界面一键识屏：**圈选涂抹 / 点按自动框选 / 全屏**三种选择，进入可选态真机 P95 ≈ 7ms（不等识别先出交互）
- 选框**可拖动、可改尺寸**；**先问后理解**——语音或打字提问（空输入即默认理解）；选中内容以**附件条**（缩略图 + 文字摘要）呈现，不挤占输入框
- 识别结果面板：实体 / 动作 chips（提取文字、翻译、搜一下）、继续问
- **「记住这个」**一键联动用户记忆（确认卡预填来源 = 屏幕识别）
- **敏感拦截**：金融 / 支付 / 密码类应用在采集前直接阻断（不截屏、不读节点）

### 🧠 用户记忆数据库
- 独立于知识库的**用户断言库**（SQLite + 审计表）：证件、地址、偏好、账号、经历
- 显式写入走**确认卡**（不静默入库）；冲突不覆盖（新旧并存、可择优采用）
- 对话自动召回注入（`<user_memory>`，机密级**永不注入**，读写全程审计可追溯）
- 记忆主页：搜索 / 分类 / 增删改查

### 🧰 系统能力工具
- 一等工具：`contacts` 通讯录增删查 · `gallery` 相册 · `file_manager` 文件搜索/复制/移动 · `settings` 亮度/音量/勿扰/蓝牙/Wi-Fi
- **语义分工**：提醒 → 日历日程，闹钟 → 时钟；注入设备时间让模型正确换算"明天下午三点"
- 真机零工具词自然问法实测全命中（模型自主选对工具，provider 侧回读验证）

### 🎨 品牌视觉统一
- 全局去除二次元 / 旧品牌元素，统一 Hermes 官方风格

---

## AI 能帮你做什么

**操控任何 App** — 微信、支付宝、抖音、淘宝、高德……凡是你能手动操作的，AI 都能操作。

```
你：帮我打开微信发消息给张三说"明天见"
AI：→ 打开微信 → 搜索张三 → 输入消息 → 发送 ✅
```

**语音随手问** — 长按按键唤起语音助手：问答、定闹钟、建提醒、查相册、调设置，一句话搞定。

**看一眼就懂** — 识屏按钮圈出屏幕任意内容，提问、翻译、提取文字，或让 AI「记住这个」。

**跨应用联动** — 微信收到地址 → 复制 → 打开高德 → 搜索 → 开始导航

**执行代码** — 通过 Termux SSH 或内置 Shell 执行命令（Python/Node.js/Shell）

**多平台消息** — 飞书、Discord 远程控制手机 AI

**MCP Server** — 将手机无障碍/截屏能力暴露给 Claude Desktop、Cursor 等外部 Agent

---

## 快速开始

### 下载安装

从 [Release 页面](https://github.com/rtzhang1202/HermesApp/releases/latest) 下载 `HermesApp-v2.0.9.apk` 安装即可。

> 语音助手需授予麦克风权限；识屏需开启无障碍服务（HermesAccessibilityService）；系统能力工具建议授予通讯录/日历/文件等运行时权限。

### 3 步上手

1. **安装** — 下载安装 HermesApp
2. **配置** — 打开 App，输入 API Key（或跳过使用内置 Key），开启无障碍 + 录屏权限
3. **开聊** — 直接对话、语音唤起，或通过飞书/Discord 发消息

---

## 核心特性（上游基座）

| 特性 | 说明 |
|------|------|
| **Playwright 模式** | 屏幕操作对齐 Playwright —— `snapshot` 获取 UI 树 + ref → `act` 操作元素 |
| **统一 exec** | 自动路由 Termux SSH（连接池 + 断线重连）或内置 Shell |
| **Context 管理** | 4 层防护：limitHistoryTurns + 工具结果裁剪 + budget guard + auto-summarization |
| **Model 智能路由** | Model ID 标准化 + Fallback Chain + API Key 轮换 + Allowlist/Blocklist |
| **Gateway SLOT 隔离** | 每个渠道消息通过独立 ChatRuntimeSlot.GATEWAY 处理，与主 UI 互不干扰 |
| **Skill 体系** | 27 个内置 Skill，设备上可自由编辑，支持 ClawHub 在线安装 |
| **Skill Recorder** | 录制用户操作步骤，AI 自动生成可复用的 Skill |
| **MCP Server** | 将无障碍/截屏能力暴露给外部 Agent（端口 8399，Streamable HTTP） |

---

## 完整能力表

### 通用 Tools

| Tool | 功能 |
|------|------|
| `device` | 屏幕操作：snapshot / tap / type / scroll / press / open（Playwright 模式） |
| `read_file` / `write_file` / `edit_file` / `list_dir` | 文件读写编辑 |
| `exec` | 执行命令（Termux SSH / 内置 Shell） |
| `web_search` / `web_fetch` | Brave 搜索 / 网页抓取 |
| `javascript` | 执行 JavaScript（QuickJS） |
| `tts` | 文本转语音 |
| `skills_search` / `skills_install` | ClawHub 技能搜索 / 安装 |
| `memory_search` / `memory_get` | 语义搜索/读取知识库记忆 |
| **`memory_save` / `memory_search`（用户记忆）** | 关于用户本人事实的存取（确认卡机制，区别于知识库） |

### Android 专属 Tools（含本版新增）

| Tool | 功能 |
|------|------|
| `list_installed_apps` / `install_app` / `start_activity` | 应用管理 |
| `contacts`（新） | 通讯录：查询 / 添加 / 删除 |
| `gallery`（新） | 相册：近期图片/视频 |
| `file_manager`（新） | 文件搜索 / 复制 / 移动 |
| `settings`（新） | 亮度 / 音量 / 勿扰 / 蓝牙 / Wi-Fi |
| `alarm` / `calendar` | 闹钟（叫醒）/ 日历日程（提醒、会议） |
| `eye` / `log` / `stop` | 摄像头拍照 / 系统日志 / 停止 Agent |

> 消息渠道（飞书 39 工具 / Discord / 设备内对话）、27 Skills、模型 Provider、配置参考等与上游一致，详见 **[完整文档](https://vcn23e479dhx.feishu.cn/wiki/UZtFwM6t9iArPVkMvRccwSran6d)** 与 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 从源码构建

```bash
# Windows（JDK 21，勿用 sh 包装器）
git clone https://github.com/rtzhang1202/HermesApp.git
cd HermesApp
./gradlew.bat assembleDebug
# APK 产出：app/build/outputs/apk/debug/app-debug.apk
```

- 分支：`dev` = 增强主线（日常工作分支）；`main` = 上游 + 基建修复
- 提交规范：`[mod]` 魔改 / `[up]` 同步上游 / `[fix]` 缺陷修复

---

## Related Projects

| 项目 | 说明 |
|------|------|
| [Hermes](https://github.com/nicepkg/hermes) | AI Agent 框架（桌面端） |
| [Hermes for Android](https://github.com/SelectXn00b/HermesApp) | 上游基座 |

## 社区

使用问题与需求请提 [Issue](https://github.com/rtzhang1202/HermesApp/issues)。

## License

[MIT](LICENSE)
