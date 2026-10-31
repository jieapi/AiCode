import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { LOCALE_PATHS } from "../src/composables/useLocale.js";
import { createPageHead, resolveSiteOrigin } from "./seo.mjs";

const root = process.cwd();
const distIndex = resolve(root, "dist/index.html");

const template = await readFile(distIndex, "utf8");

if (!template.includes('<div id="app"></div>')) {
  throw new Error("dist/index.html 中未找到 #app 挂载点，无法注入预渲染内容");
}

const siteOrigin = resolveSiteOrigin();
if (!siteOrigin) {
  console.warn("警告：未配置 SITE_URL 或 VERCEL_PROJECT_PRODUCTION_URL 生产域名；仍生成中英文页面，但不输出 canonical 和 hreflang 绝对地址。");
}

const { render } = await import(pathToFileURL(resolve(root, ".ssr/entry-server.js")).href);

for (const [locale, pathname] of Object.entries(LOCALE_PATHS)) {
  const appHtml = await render(pathname);
  const html = template
    .replace(/(<html\b[^>]*\blang=")[^"]*(")/, (_, before, after) => `${before}${locale}${after}`)
    .replace(/<title>[\s\S]*?<\/title>\s*/, "")
    .replace(/<meta name="description"[^>]*>\s*/, "")
    .replace("</head>", () => `  ${createPageHead(locale, siteOrigin).replaceAll("\n", "\n  ")}\n</head>`)
    .replace('<div id="app"></div>', () => `<div id="app">${appHtml}</div>`);
  const output = resolve(root, "dist", `.${pathname}`, "index.html");
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, html);

  console.log(`预渲染完成：${pathname}，注入 ${appHtml.length} 个字符`);
}
