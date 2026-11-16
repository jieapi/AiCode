import { access, mkdir, readdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { build } from 'vite';
import vue from '@vitejs/plugin-vue';
import * as VueRuntime from 'vue';

const PAGE_SOURCE_DIR = 'src/pages';
const PAGE_OUTPUT_DIR = 'dist/pages';
const MAX_PAGE_BYTES = 512 * 1024;
const VUE_GLOBAL_ID = '\0aicode-vue-global';
const VUE_EXPORT_NAMES = Object.keys(VueRuntime).filter(
  (name) => name !== 'default' && /^[A-Za-z_$][\w$]*$/.test(name)
);

// 页面运行时使用宿主注入的 /__aicode/vue.global.prod.js，不把 Vue 打进产物。
function vueGlobalPlugin() {
  return {
    name: 'aicode-vue-global',
    enforce: 'pre',
    resolveId(source) {
      if (source === 'vue') return VUE_GLOBAL_ID;
      return null;
    },
    load(id) {
      if (id !== VUE_GLOBAL_ID) return null;
      return [
        'const Vue = window.Vue;',
        'if (!Vue) throw new Error("Aicode page requires /__aicode/vue.global.prod.js");',
        'export default Vue;',
        ...VUE_EXPORT_NAMES.map((name) => `export const ${name} = Vue.${name};`)
      ].join('\n');
    }
  };
}

async function exists(file) {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
}

export async function buildPluginPages(dir, options) {
  const sourceDir = path.join(dir, PAGE_SOURCE_DIR);
  let entries;
  try {
    entries = await readdir(sourceDir, { withFileTypes: true });
  } catch {
    return [];
  }

  const pages = [];
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith('.html')) continue;
    const vueSource = path.join(sourceDir, path.basename(entry.name, '.html') + '.vue');
    if (!(await exists(vueSource))) {
      throw new Error(`页面 ${entry.name} 缺少同名 .vue 源码`);
    }
    pages.push({ name: entry.name, source: path.join(sourceDir, entry.name) });
  }
  if (pages.length === 0) return [];

  const outputDir = path.join(dir, PAGE_OUTPUT_DIR);
  await rm(outputDir, { recursive: true, force: true });
  await mkdir(outputDir, { recursive: true });

  await build({
    configFile: false,
    root: sourceDir,
    base: './',
    publicDir: false,
    logLevel: 'error',
    plugins: [vueGlobalPlugin(), vue()],
    build: {
      outDir: outputDir,
      emptyOutDir: true,
      target: 'es2018',
      minify: options.minify === true ? 'esbuild' : false,
      rollupOptions: {
        input: Object.fromEntries(
          pages.map((page) => [path.basename(page.name, '.html'), page.source])
        )
      }
    }
  });

  const results = [];
  for (const page of pages) {
    const outputPath = path.join(outputDir, page.name);
    const info = await stat(outputPath);
    if (info.size > MAX_PAGE_BYTES) {
      throw new Error(`页面超过 512 KiB: ${path.relative(dir, outputPath)} (${info.size} bytes)`);
    }
    results.push({ source: page.source, output: outputPath, size: info.size });
  }
  return results;
}
