<palign="中心">
<IMGsrc="docs/icon.webp"宽度="112"alt="AiCode"/>
  <h1 对齐="中心">AiCode</h1>
  <p对齐="中心">
安卓上的通用AI编码代理·Linux开发环境·支持本地与远程SSH
    <溴/>
    <一个href="README.md">中文</一个> · <一个href="README.en.md">英语</一个>
  </p>
</p>

<p对齐="中心">
  <一个href="许可"><IMGsrc="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="许可证GPL-3.0" /></一个>
<IMGsrc="https://img.shields.io/badge/Platform-Android-green.svg"alt="Android平台"/>
<一个href="https://github.com/jieapi/aicode/releases"><IMGsrc="https://img.shields.io/github/v/release/jieapi/aicode?display_name=tag&include_prereleases"alt="最新版本"/></一个>
</p>

## 关于开源协议的声明

近期发现，某闭源商业 App 大量使用了 AiCode 的代码及静态资源，甚至保留了本项目的类名与相关标识，却未遵守本项目的开源协议。与此同时，其开发者声称从未听说过 AiCode。

**AiCode 允许在遵守 [GPL-3.0](许可证) 的前提下进行商业使用。** 我们反对的不是商业化，而是使用本项目代码后，以闭源形式分发衍生作品，且未履行保留版权与许可声明、按 GPL-3.0 授权及提供对应源代码等义务的行为。开源不等于放弃版权，请尊重开源协议与开发者的劳动成果。

<palign="中心">
  <table>
    <tr>
      <td align="中心"><IMGsrc="docs/screenshots/home.png" alt="AiCode 主页 - AI 对话界面，支持代码生成与 Markdown 渲染" 宽度="270"/></td>
      <td align="中心"><IMGsrc="docs/screenshots/git.png" alt="AiCode Git 集成 - 可视化提交记录与分支管理" 宽度="270"/></td>
    </tr>
    <tr>
      <td align="中心">主页 · AI 对话</td>
      <td align="中心">Git · 提交历史</td>
    </tr>
    <tr>
      <td align="中心"><IMGsrc="docs/screenshots/container.png" alt="AiCode 容器设置 - 容器镜像管理" 宽度="270"/></td>
      <td align="中心"><IMGsrc="docs/screenshots/models.png" alt="AiCode 模型列表 - 多供应商模型管理" 宽度="270"/></td>
    </tr>
    <tr>
      <td align="中心">容器 · 镜像管理</td>
      <td align="中心">模型 · 列表配置</td>
    </tr>
  </table>
</p>

---

## 简介

AiCode 是运行在 Android 上的通用 AI Coding Agent，把一套完整的 Linux 开发环境装进手机：内置 Alpine Linux 容器与终端，AI Agent 能读写文件、执行 Shell 命令、运行构建工具，写代码、调试到构建都在手机本地完成；也可改用远程 SSH 服务器作为执行后端，把手机变成远程项目的移动工作站。

上手无需任何准备：装上 App、在「AI 供应商」配好模型即可直接开发，不用电脑，也不用自己搭建环境。AiCode 不内置模型、不绑定供应商，支持 OpenAI / Anthropic / Gemini 三类协议与自定义供应商，模型、密钥与端点都由你自行配置。

## 赞助商

| 图标 | 描述 |
|------|------|
| <img src="https://www.rainyun.com/favicon.ico" width="24" alt="RainYun" /> | **[雨云](https://www.rainyun.com/logins_)** — 本项目服务器赞助商，国产云服务商，主营云服务器与游戏云（Minecraft 等预装服务端一键开服），兼有裸金属物理机与对象存储，新用户优惠 |

## 广告

| 图标 | 描述 |
|------|------|
| <img src="https://www.qiniu.com/favicon.ico" width="24" alt="Qiniu" /> | **[七牛云 AI](https://s.qiniu.com/vUryau)** — 新用户注册赠 300 万 Token（2 年有效），支持 50+ 热门大模型 |

## 功能特性

### AI Agent

- **AI Agent** — 内置文件读写与编辑、Shell 执行、后台终端、代码与网页搜索、图片识别、待办清单等工具；流式输出并实时渲染 Markdown，长对话自动压缩上下文
- **内置浏览器** — 内置多标签 WebView 浏览器，AI 可导航、点击、填表与截图，直接在网页上完成操作；支持夜间模式与本地文件预览
- **子代理并行** — 主会话可派生独立上下文的子代理在后台并行调研、审查或对比方案，不阻塞当前对话；内置只读的 Explore 子代理，也可自定义模型、工具集与提示词
- **三种运行模式** — BUILD 正常开发、PLAN 只读规划（工具层拦截写操作）、AUTO 免授权全放行，按信任程度切换 AI 权限
- **检查点与撤销** — Agent 改代码前自动记录文件快照，对话中可一键回滚代码、对话或两者
- **技能与自动记忆** — 支持全局/项目级技能（Skills）与长期记忆，AI 可跨会话复用经验与项目约定
- **MCP 协议** — 支持连接本地（stdio）与远程（HTTP）MCP 服务器，动态扩展 AI 工具能力
- **工具授权与自定义提示词** — 逐工具配置授权规则，系统提示词支持用户覆盖且 App 升级不丢失

### 模型与供应商

- **模型无关** — 不内置模型、不绑定供应商，模型、密钥与端点自行配置
- **协议兼容** — 兼容 OpenAI / Anthropic / Gemini 三类协议，内置多家官方预设，也支持自定义供应商，模型列表与单价可自定义
- **多 Key 轮换** — 同一供应商可配多个 Key，按顺序或轮询自动轮换，思考强度可调

### 开发环境

- **内置终端与容器** — 基于 Termux 与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端多标签、可后台常驻
- **远程 SSH 模式** — 把远程服务器作为执行后端，命令、文件与终端都作用于远端项目
- **Shizuku 执行后端** — 借助 Shizuku，AI 可在宿主 Android 系统上执行 shell 与 adb 命令，用于调试或操作其他设备（需逐次授权确认）
- **文件树与代码编辑器** — 缩进式文件树点开即进全屏编辑器，支持主流语言语法高亮与 Markdown 预览；AI 回复里的 `文件:行号` 链接可直接跳转到对应行，本地与远程 SSH 工作区都支持
- **Git 集成** — 可视化管理状态、分支、提交历史、差异与标签，支持暂存与回退改动、署名与凭据配置
- **工作区同步** — 支持 SFTP / FTP 同步，内置 FTP 服务端方便电脑端管理文件

### 使用体验

- **平板与大屏适配** — 按窗口宽度自适应：宽屏常驻侧边栏，聊天旁并排显示代码或终端；变窄时退回单栏
- **Token 统计** — 按渠道与模型统计用量、估算费用，可下钻查看调用明细
- **外观与语言** — 主题明暗、预设配色、莫奈取色、自定义背景图，中英双语界面
- **网络代理** — 支持全局代理与供应商级代理分别配置
- **备份与还原** — 加密导出/导入供应商配置、凭据、聊天历史与工作区文件

## 快速开始

| 项目 | 说明 |
|------|------|
| 系统要求 | Android 8.0+（API 26），arm64-v8a / x86_64 |
| 下载地址 | [GitHub Releases](https://github.com/jieapi/aicode/releases/latest)：真机选 `armsolo`、模拟器选 `x86solo`、通用选 `universal` 包 |
| 快速上手 | 「设置 → AI 供应商」配模型 →「容器与镜像」选本地或 SSH → 新建会话开始对话 |
| 更新记录 | [Releases](https://github.com/jieapi/aicode/releases)（历史版本与更新说明） |
| 使用指南 | [在线文档](https://aicode.murk.top)：快速上手、使用手册与进阶教程（与 App 内置文档同源） |

## Star

如果 AiCode 对你有帮助，欢迎 [Star](https://github.com/jieapi/aicode) 支持，让更多开发者看到这个项目。

## Star History

<a href="https://www.star-history.com/?repos=jieapi%2Faicode&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&legend=top-left" />
 </picture>
</a>

## 反馈与贡献

- **交流群**：加入 [AiCode QQ 交流群](https://qm.qq.com/q/ByvqODJdIs)（群号：1107110698），交流使用心得、反馈问题
- **Bug 反馈**：到 [Issues](https://github.com/jieapi/aicode/issues) 提交，附上复现步骤、设备型号与系统版本，便于定位
- **功能建议**：想加新功能或改进，欢迎先在 [Issues](https://github.com/jieapi/aicode/issues) 讨论
- **贡献代码**：欢迎提交 [Pull Request](https://github.com/jieapi/aicode/pulls)

## 致谢

- [OpenCode](https://github.com/anomalyco/opencode) — 终端 AI 编码工具，本项目的核心灵感来源
- [Termux](https://github.com/termux/termux-app) — Android 终端模拟器，提供了终端组件与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — 跨平台 LLM 聊天客户端，AI 对话界面设计参考

## 免责声明

AiCode 按「现状」提供，仅供学习、研究与合法的软件开发用途。严禁将本软件用于任何违法或侵权用途，包括但不限于未经授权入侵、破解、逆向工程他人软件或系统，绕过技术保护措施，窃取或泄露他人数据，传播恶意代码等侵害他人合法权益的行为。

使用者应自行承担使用本软件的全部风险与责任；对于使用者因使用或无法使用本软件而产生的任何损失，以及其利用本软件实施的任何行为及后果，作者与贡献者均不承担任何责任。

若不同意上述声明，请立即停止使用并卸载本软件。

## 开源协议

本项目基于 [GPL-3.0](LICENSE) 协议开源。
