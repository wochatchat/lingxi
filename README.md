# 灵犀（LingXi）

**无唤醒词常开 AI 伴侣** —— "身无彩凤双飞翼，心有犀点通"。无需唤醒词，永远在听，全双工对话，三层记忆，能动手，会主动。

## 状态

v0.1.0 骨架建设中。详细路线图见 [改进规划 v1](共享文档/改进规划-v1.md)。

## 主要功能（按里程碑）

| 里程碑 | 内容 |
|---|---|
| M1 | 常听管线 + 全双工对话 + 耳语胶囊 |
| M2 | 三层记忆 + 意图路由 + 委托任务 + 晨报 |
| M3 | UI 代操作 + 截屏问答 + 功耗面板 |

> **F9 合规提示**：UI 代操作功能（辅助服务读屏代操作）默认关闭、逐项授权、操作日志可审计。**在第三方 App 内使用辅助服务可能违反该 App 用户协议**（账号限制风险）。本 App 比 jev-chat（MIT）多一层安全防护，但不保证完全合规，使用前请自行评估。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- Hilt（依赖注入）+ Room + DataStore
- minSdk 26 / targetSdk 34
- CI: GitHub Actions（编译/测试/签名测试版/正式 Release）

## 执照

MIT License。设计参考：
- [jev-chat](https://github.com/jev-chat/jev-chat-jarvis)（MIT），具体引用见 PRD §16。