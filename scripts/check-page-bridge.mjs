#!/usr/bin/env node
/**
 * 对账插件页面桥的统一 dispatcher、页面专属分支与页面 SDK 调用点。
 *
 * 公共页面能力由 `PluginHostApiDispatcher.dispatchInternal` 提供；普通页面和浮层桥只保留
 * 各自的生命周期与权限专属方法。页面 SDK 调用但三者都没有实现时是运行期硬错误。
 *
 * 用法：node scripts/check-page-bridge.mjs
 * 退出码：0 一致；1 有硬错误或解析失败。
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const HOST_BRIDGES = [
  'app/src/main/java/com/aicode/feature/plugin/presentation/PluginWebBridge.kt',
  'app/src/main/java/com/aicode/feature/plugin/overlay/PluginOverlayBridge.kt',
  'app/src/main/java/com/aicode/feature/plugin/runtime/PluginHostApiDispatcher.kt'
];
const PAGE_SDK = 'app/src/main/assets/plugin-ui/aicode.js';

function collectDispatchBodies(source) {
  const bodies = [];
  for (const match of source.matchAll(/fun (dispatchInternal|dispatch)\s*\(/g)) {
    const bodyStart = source.indexOf('{', match.index);
    let depth = 0;
    for (let i = bodyStart; i < source.length; i += 1) {
      if (source[i] === '{') depth += 1;
      else if (source[i] === '}' && --depth === 0) {
        bodies.push(source.slice(bodyStart, i + 1));
        break;
      }
    }
  }
  return bodies;
}

function collectKotlinMethods(file) {
  const source = readFileSync(join(root, file), 'utf8');
  const bodies = collectDispatchBodies(source);
  const methods = new Set();
  for (const body of bodies) {
    for (const line of body.split('\n')) {
      const branch = line.match(/^\s*((?:"[A-Za-z][A-Za-z0-9_.]*"\s*,\s*)*"[A-Za-z][A-Za-z0-9_.]*")\s*->/);
      if (!branch) continue;
      for (const name of branch[1].matchAll(/"([A-Za-z][A-Za-z0-9_.]*)"/g)) methods.add(name[1]);
    }
  }
  return { methods, error: bodies.length === 0 ? `未找到 dispatch 函数：${file}` : null };
}

function collectPageSdkCalls(file) {
  const source = readFileSync(join(root, file), 'utf8');
  const calls = new Set();
  for (const match of source.matchAll(/call\(\s*'([A-Za-z][\w.]*)'/g)) calls.add(match[1]);
  return calls;
}

function main() {
  const problems = [];
  const hostMethods = new Set();
  HOST_BRIDGES.forEach((file) => {
    const { methods, error } = collectKotlinMethods(file);
    if (error) problems.push(error);
    methods.forEach((method) => hostMethods.add(method));
  });
  const pageCalls = collectPageSdkCalls(PAGE_SDK);
  if (pageCalls.size === 0) problems.push(`未从 ${PAGE_SDK} 解析到任何 call(...)`);
  const unimplemented = [...pageCalls].filter((method) => !hostMethods.has(method)).sort();
  if (unimplemented.length > 0) {
    problems.push(`页面 SDK 调用但宿主没有对应分支：\n${unimplemented.map((m) => `  - ${m}`).join('\n')}`);
  }
  if (problems.length > 0) {
    console.error('插件页面桥不一致：');
    problems.forEach((problem) => console.error(`\n${problem}`));
    process.exit(1);
  }
  console.log(`插件页面桥一致：宿主 ${hostMethods.size} 个分支，页面 SDK ${pageCalls.size} 个调用点`);
}

main();
