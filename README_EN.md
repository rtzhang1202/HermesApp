# HermesApp — AI Agent for Android (Enhanced Edition)

[![Release](https://img.shields.io/badge/Release-v2.0.9-blue.svg)](https://github.com/rtzhang1202/HermesApp/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://www.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Let AI truly control your Android phone.**

A 1:1 Kotlin port of the [Hermes](https://github.com/nicepkg/hermes) agent loop embedded into Android, delivering full AI Agent capabilities on your phone: see the screen, tap apps, run code, connect platforms.

This repository is a **deeply enhanced fork** (`dev` branch is the main development line), reinforcing four major directions on top of the upstream: **Voice Assistant, Screen Recognition, System Capability Tools and User Memory**, plus a global brand visual unification.

**[中文文档](README.md)**

---

## ⭐ Key Enhancements

### 🎙 Voice Assistant (Gemini / Super XiaoAi style)
- Full-screen voice UI: dark glassmorphism + color-bar waveform + chat bubbles + suggestion chips; **external button wake-up** (e.g. KeyPilot long-press of the Essential key)
- **Cloud ASR first** (429 backoff, local fallback only on network failure), Chinese/English locked
- **Sensitive capture pipeline**: adaptive VAD (noise floor = 30s p80 percentile ×1.5, lower bound 80) + `NoiseSuppressor` hardware denoise + software AGC before upload — no missed words on soft speech, zero false triggers on silence
- Single-shot conversation mode (stops after answering, no auto re-listening) + empty-answer fallback
- **Real image rendering** inside chat bubbles (link tags / local paths / markdown; tap to view fullscreen)

### 🔍 Screen Lens
- One-tap screen understanding from the voice UI: **lasso / tap-to-select node / fullscreen**; selection mode ready in P95 ≈ 7ms on real device (interaction first, recognition later)
- Selection box is **draggable and resizable**; **ask-then-understand** — ask by voice or typing (empty input = default understanding); selected content shown as an **attachment bar** (thumbnail + text summary) instead of polluting the input box
- Result sheet: entity / action chips (extract text, translate, search), follow-up questions
- **"Remember this"** hands off to User Memory (confirmation card pre-filled with source = screen recognition)
- **Sensitive blocking**: finance / payment / password apps are blocked before any capture (no screenshot, no node read)

### 🧠 User Memory Database
- A **user-assertion store** independent of the knowledge base (SQLite + audit table): IDs, addresses, preferences, accounts, experiences
- Explicit writes go through a **confirmation card** (never silently persisted); conflicts never overwrite (old & new coexist, user picks)
- Automatic recall injection into conversations (`<user_memory>`; secrets **never injected**, full read/write audit trail)
- Memory home page: search / categories / CRUD

### 🧰 System Capability Tools
- First-class tools: `contacts` · `gallery` · `file_manager` · `settings` (brightness / volume / DND / Bluetooth / Wi-Fi)
- **Semantic routing**: reminders → calendar events, wake-ups → alarms; device time injected so "tomorrow 3pm" resolves correctly
- Verified on real device with zero tool-hint natural language (model picks the right tool on its own, provider-side read-back receipts)

### 🎨 Brand Visual Unification
- All legacy anime / old-brand elements removed; unified to the official Hermes style

---

## What Can AI Do for You

**Control Any App** — WeChat, Alipay, TikTok, Taobao, Maps… Anything you can do manually, AI can do too.

```
You: Open WeChat and send "See you tomorrow" to John
AI:  → Open WeChat → Search John → Type message → Send ✅
```

**Ask by Voice** — Long-press the button to wake the assistant: Q&A, alarms, reminders, gallery lookups, settings — one sentence each.

**Understand Your Screen** — Circle anything on screen with the Lens button: ask, translate, extract text, or let the AI "remember this".

**Cross-App Workflows** — Copy address from WeChat → Open Maps → Search → Start navigation

**Run Code** — Execute Python/Node.js/Shell via Termux SSH or built-in Shell

**Multi-Platform Messaging** — Control your phone AI remotely via Feishu or Discord

**MCP Server** — Expose phone accessibility/screenshot to external agents like Claude Desktop, Cursor

---

## Quick Start

### Download & Install

Download `HermesApp-v2.0.9.apk` from the [Release page](https://github.com/rtzhang1202/HermesApp/releases/latest).

> Voice assistant needs the microphone permission; Screen Lens needs the Accessibility service (HermesAccessibilityService) enabled; system capability tools work best with contacts / calendar / storage runtime permissions.

### 3 Steps to Get Started

1. **Install** — Download and install HermesApp
2. **Configure** — Open the app, enter an API Key (or skip to use the built-in Key), enable Accessibility + Screen Capture permissions
3. **Chat** — Talk directly, wake by voice, or send messages via Feishu / Discord

---

## Core Features (Upstream Base)

| Feature | Description |
|---------|-------------|
| **Playwright Mode** | Screen ops aligned with Playwright — `snapshot` gets UI tree + ref → `act` operates elements |
| **Unified exec** | Auto-routes to Termux SSH (connection pool + auto-reconnect) or built-in Shell |
| **Context Management** | 4-layer protection: limitHistoryTurns + tool result trimming + budget guard + auto-summarization |
| **Model Smart Routing** | Model ID normalization + Fallback Chain + API Key rotation + Allowlist/Blocklist |
| **Gateway SLOT Isolation** | Each channel message processed via independent ChatRuntimeSlot.GATEWAY, isolated from main UI |
| **Skill System** | 27 built-in Skills editable on device, ClawHub online installation |
| **Skill Recorder** | Record user operations step-by-step, AI auto-generates reusable Skills |
| **MCP Server** | Expose accessibility/screenshot to external agents (port 8399, Streamable HTTP) |

---

## Full Capability Table

### General Tools

| Tool | Function |
|------|----------|
| `device` | Screen ops: snapshot / tap / type / scroll / press / open (Playwright mode) |
| `read_file` / `write_file` / `edit_file` / `list_dir` | File read/write/edit |
| `exec` | Execute commands (Termux SSH / built-in Shell) |
| `web_search` / `web_fetch` | Brave search / page fetch |
| `javascript` | Execute JavaScript (QuickJS) |
| `tts` | Text-to-speech |
| `skills_search` / `skills_install` | ClawHub skill search / install |
| `config_get` / `config_set` | Read/write config entries |
| `memory_search` / `memory_get` | Semantic search/read of knowledge-base memory |
| **`memory_save` / `memory_search` (User Memory)** | Facts about the user (confirmation-card flow; distinct from the knowledge base) |

### Android-Specific Tools (incl. new in this edition)

| Tool | Function |
|------|----------|
| `list_installed_apps` / `install_app` / `start_activity` | App management |
| `contacts` (new) | Address book: query / add / delete |
| `gallery` (new) | Recent images & videos |
| `file_manager` (new) | File search / copy / move |
| `settings` (new) | Brightness / volume / DND / Bluetooth / Wi-Fi |
| `alarm` / `calendar` | Alarms (wake-up) / calendar events (reminders, meetings) |
| `feishu_send_image` / `eye` / `log` / `stop` | Send image via Feishu / camera capture / system log / stop agent |

> Messaging channels (Feishu 39 tools / Discord / in-app chat), 27 Skills, supported models and the configuration reference (`/sdcard/.hermes/config/openclaw.json`) are identical to upstream — see the **[full docs (Chinese)](https://vcn23e479dhx.feishu.cn/wiki/UZtFwM6t9iArPVkMvRccwSran6d)** and [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Build from Source

```bash
# Windows (JDK 21; do not use the sh wrapper)
git clone https://github.com/rtzhang1202/HermesApp.git
cd HermesApp
./gradlew.bat assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

- Branches: `dev` = enhanced mainline (daily work); `main` = upstream + infrastructure fixes
- Commit prefixes: `[mod]` modifications / `[up]` upstream sync / `[fix]` bug fixes

---

## Related Projects

| Project | Description |
|---------|-------------|
| [Hermes](https://github.com/nicepkg/hermes) | AI Agent framework (Desktop) |
| [Hermes for Android](https://github.com/SelectXn00b/HermesApp) | Upstream base |

## Community

Please open an [Issue](https://github.com/rtzhang1202/HermesApp/issues) for questions and requests.

## License

[MIT](LICENSE)
