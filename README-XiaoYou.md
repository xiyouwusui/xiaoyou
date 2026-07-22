# 小柚 (XiaoYou)

基于 [OmniBot](https://github.com/omnimind-ai/OmniBot) 的个人非商业 Fork，专注 Codex CLI 和 Claude Code CLI 终端体验。

## 与原版 OmniBot 的区别

| 功能 | OmniBot (小万) | 小柚 (XiaoYou) |
|------|---------------|----------------|
| AI Agent | ✅ 内置 Agent | ❌ 移除 |
| Codex CLI | ✅ 单配置 | ✅ 多配置（多个中转站） |
| Claude Code CLI | ❌ 无 | ✅ 新增，支持多配置 |
| 终端环境 | Alpine 默认 | Ubuntu 默认 |
| 本地模型 | ✅ 有 | ❌ 移除 |
| iMessage | ✅ 有 | ✅ 保留 |
| MCP 协议 | ✅ 有 | ✅ 保留 |
| 浏览器自动化 | ✅ 有 | ✅ 保留 |
| 日历/闹钟 | ✅ 有 | ✅ 保留 |
| 文件管理 | ✅ 有 | ✅ 保留 |
| 音乐播放 | ✅ 有 | ✅ 保留 |
| 技能系统 | ✅ 有 | ✅ 保留 |
| 定时任务 | ✅ 有 | ✅ 保留 |
| 记忆系统 | ✅ 有 | ✅ 保留 |

## 编译方式

不需要本地开发环境！通过 GitHub Actions 自动编译：

1. Fork 本仓库到你的 GitHub
2. 进入仓库 → Actions 标签页
3. 选择 "Build Debug APK" 工作流
4. 点击 "Run workflow" 手动触发编译
5. 等待编译完成（约 20-40 分钟）
6. 在编译结果中下载 `xiaoyou-debug-apk` 产物
7. 安装 APK 到手机

## 许可证

继承自 OmniBot 的 AGPL v3 许可证（个人非商业用途）。
