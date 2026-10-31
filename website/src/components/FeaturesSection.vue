<template>
  <section class="section-y" id="features">
    <div class="container">
      <div class="section-header reveal" v-reveal>
        <h2 class="section-header__title">功能特性</h2>
        <p class="section-header__subtitle">围绕移动端真实软件开发打造的 AI Agent 与开发环境</p>
      </div>

      <div class="features-grid">
        <article
          v-for="feature in features"
          :key="feature.title"
          class="feature-card reveal"
          v-reveal
          :style="{ '--delay': feature.delay }"
        >
          <div class="feature-card__icon"><i :class="feature.icon"></i></div>
          <h3 class="feature-card__title">{{ feature.title }}</h3>
          <p class="feature-card__desc">{{ feature.desc }}</p>
          <div class="feature-card__orb"></div>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup>
const features = [
  {
    icon: "ri-robot-2-line",
    title: "AI Agent 与内置浏览器",
    desc: "内置文件读写与编辑、Shell 执行、后台终端、代码与网页搜索、图片识别、待办清单与多标签 WebView 浏览器；支持派生独立上下文的子代理在后台并行调研。",
    delay: "0.05s"
  },
  {
    icon: "ri-shield-check-line",
    title: "三种模式与检查点撤销",
    desc: "提供 BUILD 正常开发、PLAN 只读规划（工具层拦截写操作）、AUTO 免授权全放行三种模式；Agent 改代码前自动记录文件快照，对话中可一键回滚代码、对话或两者。",
    delay: "0.1s"
  },
  {
    icon: "ri-terminal-box-line",
    title: "内置终端与 Linux 容器",
    desc: "基于 Termux 与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端支持多标签与后台常驻运行。",
    delay: "0.15s"
  },
  {
    icon: "ri-server-line",
    title: "远程 SSH 与 Shizuku 后端",
    desc: "可把远程 SSH 服务器作为执行后端，命令、文件与终端直接作用于远端项目；也支持借助 Shizuku 在宿主 Android 系统上执行 shell 与 adb 调试命令。",
    delay: "0.2s"
  },
  {
    icon: "ri-git-merge-line",
    title: "代码编辑器、Git 与同步",
    desc: "缩进式文件树点开即进全屏语法高亮编辑器，支持平板双栏并排显示；内置可视化 Git 管理状态、分支、提交历史与差异，支持 SFTP / FTP 同步与内置 FTP 服务端。",
    delay: "0.25s"
  },
  {
    icon: "ri-puzzle-2-line",
    title: "模型无关、MCP 与技能记忆",
    desc: "兼容 OpenAI / Anthropic / Gemini 协议与自定义供应商，支持多 Key 自动轮换与 Token 费用统计；支持连接本地与远程 MCP 服务器、全局/项目级技能（Skills）与长期记忆。",
    delay: "0.3s"
  }
];
</script>
