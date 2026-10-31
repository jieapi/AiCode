import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const root = process.cwd();
const distIndex = resolve(root, "dist/index.html");

const { render } = await import(pathToFileURL(resolve(root, ".ssr/entry-server.js")).href);
const appHtml = await render();
const template = await readFile(distIndex, "utf8");

if (!template.includes('<div id="app"></div>')) {
  throw new Error("dist/index.html 中未找到 #app 挂载点，无法注入预渲染内容");
}

await writeFile(
  distIndex,
  template.replace('<div id="app"></div>', `<div id="app">${appHtml}</div>`)
);

console.log(`✓ 预渲染完成，注入 ${appHtml.length} 个字符`);
