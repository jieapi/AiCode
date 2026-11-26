#!/usr/bin/env node
/**
 * 把插件的运行时入口与页面源码打包成宿主可直接加载的产物。
 *
 * 用法：
 *   node plugins/build.mjs
 *   node plugins/build.mjs --watch
 *   node plugins/build.mjs --plugin capability-demo
 *   node plugins/build.mjs --minify
 *
 * 运行时入口取自 plugin.json 的 runtime.entry；页面源码在 src/pages/*.html，
 * 构建后写入 pages/*.html。
 */

import { build, context } from 'esbuild';
import { watch } from 'node:fs';
import { access, readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildPluginPages } from './build-pages.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PLUGIN_ROOTS = ['plugins/samples'];
const ENTRY_CANDIDATES = ['src/index.ts', 'src/main.ts'];
const DEFAULT_OUTPUT = 'dist/main.js';
const SDK_ALIAS = {
  '@jieapi/plugin-sdk': path.join(ROOT, 'plugins/sdk/src/index.ts')
};
const MAX_ENTRY_BYTES = 512 * 1024;

async function exists(file) {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
}

async function resolveOutput(pluginDir) {
  const manifestPath = path.join(pluginDir, 'plugin.json');
  if (!(await exists(manifestPath))) {
    throw new Error(`${pluginDir} 缺少 plugin.json`);
  }
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  const entry = manifest?.runtime?.entry;
  if (typeof entry !== 'string' || !entry) return path.join(pluginDir, DEFAULT_OUTPUT);
  const output = path.join(pluginDir, entry);
  if (!output.startsWith(pluginDir + path.sep)) {
    throw new Error(`runtime.entry 不能指向插件目录外: ${entry}`);
  }
  return output;
}

async function discover(rootName) {
  const root = path.join(ROOT, rootName);
  let entries;
  try {
    entries = await readdir(root, { withFileTypes: true });
  } catch {
    return [];
  }
  const plugins = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const pluginDir = path.join(root, entry.name);
    if (!(await exists(path.join(pluginDir, 'plugin.json')))) continue;
    let entryPath = null;
    for (const candidate of ENTRY_CANDIDATES) {
      const candidatePath = path.join(pluginDir, candidate);
      if (await exists(candidatePath)) {
        entryPath = candidatePath;
        break;
      }
    }
    plugins.push({ name: entry.name, dir: pluginDir, entry: entryPath });
  }
  return plugins;
}

async function discoverPlugins() {
  const plugins = [];
  for (const rootName of PLUGIN_ROOTS) {
    plugins.push(...(await discover(rootName)));
  }
  return plugins;
}

async function externalDependencies(pluginDir) {
  const manifest = JSON.parse(await readFile(path.join(pluginDir, 'plugin.json'), 'utf8'));
  if (!manifest?.runtime?.dependencies) return [];
  const packagePath = path.join(pluginDir, 'package.json');
  if (!(await exists(packagePath))) return [];
  const pkg = JSON.parse(await readFile(packagePath, 'utf8'));
  return Object.keys(pkg.dependencies || {});
}

function parseArgs(argv) {
  const options = { watch: false, minify: false, plugin: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--watch') options.watch = true;
    else if (arg === '--minify') options.minify = true;
    else if (arg === '--plugin') {
      options.plugin = argv[i + 1];
      if (!options.plugin) throw new Error('--plugin 需要插件目录名');
      i += 1;
    } else if (arg === '--help' || arg === '-h') {
      console.log('用法: node plugins/build.mjs [--watch] [--minify] [--plugin <目录名>]');
      process.exit(0);
    } else {
      throw new Error(`未知参数: ${arg}`);
    }
  }
  return options;
}

function createReporter(plugin, outfile, watch) {
  return {
    name: 'aicode-plugin-reporter',
    setup(pluginBuild) {
      pluginBuild.onEnd(async (result) => {
        if (result.errors.length > 0) return;
        const info = await stat(outfile);
        const kib = (info.size / 1024).toFixed(1);
        console.log(`构建 ${plugin.name} -> ${path.relative(ROOT, outfile)} (${kib} KiB)`);
        if (info.size > MAX_ENTRY_BYTES) {
          const message = `运行时入口超过 512 KiB 上限: ${plugin.name} (${kib} KiB)`;
          if (watch) console.warn(`警告: ${message}`);
          else throw new Error(message);
        }
      });
    }
  };
}

async function buildOptions(plugin, options) {
  const outfile = await resolveOutput(plugin.dir);
  const external = await externalDependencies(plugin.dir);
  return {
    entryPoints: [plugin.entry],
    outfile,
    bundle: true,
    platform: 'node',
    format: 'cjs',
    target: 'node12',
    alias: SDK_ALIAS,
    external,
    minify: options.minify,
    sourcemap: false,
    legalComments: 'none',
    logLevel: 'warning',
    plugins: [createReporter(plugin, outfile, options.watch)]
  };
}

async function buildAndReportPages(plugin, options) {
  const results = await buildPluginPages(plugin, options);
  for (const result of results) {
    const kib = (result.size / 1024).toFixed(1);
    console.log(
      `构建页面 ${plugin.name}/${path.basename(result.output)} -> ${path.relative(ROOT, result.output)} (${kib} KiB)`
    );
  }
  return results;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  let plugins = await discoverPlugins();
  if (options.plugin) {
    const wanted = path.resolve(ROOT, options.plugin);
    plugins = plugins.filter(
      (plugin) => plugin.name === options.plugin || plugin.dir === wanted
    );
    if (plugins.length === 0) {
      throw new Error(`没有找到要构建的插件: ${options.plugin}`);
    }
  }
  if (plugins.length === 0) {
    console.log('没有找到插件');
    return;
  }

  const runtimePlugins = plugins.filter((plugin) => plugin.entry);
  if (options.watch) {
    const contexts = [];
    for (const plugin of runtimePlugins) {
      const ctx = await context(await buildOptions(plugin, options));
      await ctx.watch();
      contexts.push(ctx);
    }

    const pageWatchers = [];
    const pageTimers = new Map();
    for (const plugin of plugins) {
      const pagesDir = path.join(plugin.dir, 'src/pages');
      if (!(await exists(pagesDir))) continue;
      await buildAndReportPages(plugin, options);
      const watcher = watch(pagesDir, () => {
        const previous = pageTimers.get(plugin.dir);
        if (previous) clearTimeout(previous);
        pageTimers.set(
          plugin.dir,
          setTimeout(() => {
            buildAndReportPages(plugin, options).catch((error) => {
              console.error(`页面构建失败 ${plugin.name}: ${error.message}`);
            });
          }, 150)
        );
      });
      pageWatchers.push(watcher);
    }

    console.log(
      `正在监听 ${runtimePlugins.length} 个运行时插件、${pageWatchers.length} 个页面目录，按 Ctrl+C 退出`
    );
    process.on('SIGINT', async () => {
      for (const watcher of pageWatchers) watcher.close();
      for (const timer of pageTimers.values()) clearTimeout(timer);
      await Promise.all(contexts.map((ctx) => ctx.dispose()));
      process.exit(0);
    });
    return;
  }

  for (const plugin of runtimePlugins) {
    await build(await buildOptions(plugin, options));
  }
  for (const plugin of plugins) {
    await buildAndReportPages(plugin, options);
  }
}

main().catch((error) => {
  console.error(`构建失败: ${error.message}`);
  process.exit(1);
});
