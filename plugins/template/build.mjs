import { build, context } from 'esbuild';
import { readFile } from 'node:fs/promises';
import { existsSync, watch } from 'node:fs';
import path from 'node:path';
import { buildPluginPages } from './build-pages.mjs';

const args = new Set(process.argv.slice(2));
const manifest = JSON.parse(await readFile('plugin.json', 'utf8'));
const entry = manifest?.runtime?.entry || 'dist/main.js';
const outfile = path.resolve(entry);

if (!outfile.startsWith(process.cwd() + path.sep)) {
  throw new Error(`runtime.entry 不能指向项目目录外: ${entry}`);
}

const options = {
  entryPoints: ['src/index.ts'],
  outfile,
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node12',
  minify: args.has('--minify'),
  sourcemap: false,
  legalComments: 'none',
  logLevel: 'warning'
};

async function buildPages() {
  const pages = await buildPluginPages(process.cwd(), { minify: args.has('--minify') });
  for (const page of pages) {
    const kib = (page.size / 1024).toFixed(1);
    console.log(`构建页面 -> ${path.relative(process.cwd(), page.output)} (${kib} KiB)`);
  }
}

if (args.has('--watch')) {
  const ctx = await context(options);
  await ctx.watch();
  await buildPages();

  const pagesDir = path.join(process.cwd(), 'src/pages');
  let timer;
  const rebuild = () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      buildPages().catch((error) => console.error(`页面构建失败: ${error.message}`));
    }, 150);
  };
  if (existsSync(pagesDir)) {
    try {
      watch(pagesDir, { recursive: true }, rebuild);
    } catch {
      watch(pagesDir, rebuild);
    }
  }
  console.log('正在监听 src/ 与 src/pages/，按 Ctrl+C 退出');
} else {
  await build(options);
  await buildPages();
  console.log(`构建完成 -> ${path.relative(process.cwd(), outfile)}`);
}
