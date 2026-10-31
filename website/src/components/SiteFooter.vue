<template>
  <footer class="footer">
    <div class="container">
      <div class="footer__main">
        <div class="footer__brand-block">
          <a href="#top" class="footer__brand" aria-label="AiCode，返回顶部">
            <img src="/assets/logo.png" alt="" width="32" height="32" class="footer__logo" />
            <span>AiCode</span>
          </a>
          <p class="footer__description">
            Android 上的通用 AI Coding Agent，提供 Linux 开发环境，支持本地与远程 SSH。
          </p>
        </div>

        <nav class="footer__navigation" aria-label="页脚导航">
          <div v-for="column in columns" :key="column.title" class="footer__group">
            <h2 class="footer__heading">{{ column.title }}</h2>
            <ul class="footer__links" role="list">
              <li v-for="link in column.links" :key="link.href">
                <a :href="link.href" target="_blank" rel="noopener noreferrer" class="footer__link">
                  <span>{{ link.label }}</span>
                  <span v-if="link.detail" class="footer__link-detail">{{ link.detail }}</span>
                </a>
              </li>
            </ul>
          </div>
        </nav>
      </div>

      <div class="footer__bottom">
        <p>© 2026 AiCode · GPL-3.0 开源许可</p>
        <p>仅供学习、研究与合法的软件开发用途</p>
      </div>
    </div>
  </footer>
</template>

<script setup>
const columns = [
  {
    title: "快速入口",
    links: [
      { label: "GitHub Releases", href: "https://github.com/jieapi/aicode/releases/latest" },
      { label: "安装包下载", href: "https://aicode.murk.top/download" },
      { label: "使用指南", href: "https://aicode.murk.top" },
      { label: "快速上手", href: "https://aicode.murk.top/guide/quick-start" }
    ]
  },
  {
    title: "反馈与贡献",
    links: [
      { label: "QQ 交流群", detail: "1107110698", href: "https://qm.qq.com/q/ByvqODJdIs" },
      { label: "问题与建议", href: "https://github.com/jieapi/aicode/issues" },
      { label: "贡献代码", href: "https://github.com/jieapi/aicode/pulls" }
    ]
  },
  {
    title: "致谢项目",
    links: [
      { label: "OpenCode", href: "https://github.com/anomalyco/opencode" },
      { label: "Termux", href: "https://github.com/termux/termux-app" },
      { label: "Kelivo", href: "https://github.com/Chevey339/kelivo" }
    ]
  }
];
</script>

<style scoped>
.footer {
  background: var(--bg-elevated);
  border-top: 1px solid var(--border);
}

.footer__main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  gap: clamp(40px, 7vw, 96px);
  padding: 56px 0 40px;
}

.footer__brand-block {
  min-width: 0;
}

.footer__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  border-radius: 8px;
  color: var(--fg);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.2;
}

.footer__logo {
  width: 32px;
  height: 32px;
  border-radius: 8px;
}

.footer__description {
  max-width: 280px;
  margin-top: 12px;
  color: var(--fg-secondary);
  font-size: 14px;
  line-height: 1.85;
}

.footer__navigation {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
  min-width: 0;
}

.footer__group {
  min-width: 0;
}

.footer__heading {
  margin: 12px 0;
  color: var(--fg);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}

.footer__links {
  list-style: none;
}

.footer__link {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0 6px;
  max-width: 100%;
  min-height: 44px;
  padding: 8px 0;
  border-radius: 4px;
  color: var(--fg-secondary);
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  transition: color 0.2s ease;
}

.footer__link-detail {
  font-family: var(--font-mono);
  font-size: 11px;
}

.footer__brand:focus-visible,
.footer__link:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 4px;
}

.footer__bottom {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 24px;
  padding: 20px 0 24px;
  border-top: 1px solid var(--border);
  color: var(--fg-secondary);
  font-size: 12px;
  line-height: 1.7;
}

@media (hover: hover) {
  .footer__link:hover {
    color: var(--fg);
    text-decoration: underline;
    text-decoration-color: var(--primary);
    text-underline-offset: 4px;
  }
}

@media (max-width: 860px) {
  .footer__main {
    grid-template-columns: minmax(0, 1fr);
    gap: 28px;
    padding-top: 40px;
  }

  .footer__description {
    max-width: 400px;
  }
}

@media (max-width: 640px) {
  .footer__main {
    gap: 24px;
    padding-bottom: 28px;
  }

  .footer__navigation {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px 24px;
  }

  .footer__heading {
    margin-top: 0;
    margin-bottom: 8px;
  }

  .footer__group:last-child {
    grid-column: 1 / -1;
    padding-top: 20px;
    border-top: 1px solid var(--border-subtle);
  }

  .footer__group:last-child .footer__links {
    display: flex;
    flex-wrap: wrap;
    column-gap: 24px;
  }

  .footer__bottom {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
