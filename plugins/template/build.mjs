import { build, context } from 'esbuild';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

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

if (args.has('--watch')) {
  const ctx = await context(options);
  await ctx.watch();
  console.log('正在监听 src/，按 Ctrl+C 退出');
} else {
  await build(options);
  console.log(`构建完成 -> ${path.relative(process.cwd(), outfile)}`);
}
