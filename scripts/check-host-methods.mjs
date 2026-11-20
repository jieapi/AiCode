#!/usr/bin/env node
/**
 * 对账统一宿主 dispatcher 与插件 SDK 的进程侧方法表。
 *
 * 统一 dispatcher 的 `dispatchInternal` 分支是进程侧能力的唯一事实源；
 * `plugins/sdk/types/host.d.ts` 的 `HostMethodMap` 是插件作者看到的声明的唯一事实源。
 *
 * 用法：node scripts/check-host-methods.mjs
 * 退出码：0 一致；1 有差异或解析失败。
 */

import { readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const HOST_KT = 'app/src/main/java/com/aicode/feature/plugin/runtime/PluginHostApiDispatcher.kt';
const SDK_DTS = 'plugins/sdk/types/host.d.ts';
const DTS_PATTERN = /^\s*'(host\.[A-Za-z0-9_.]+)'\s*:/gm;

function dispatchInternalBody(source) {
  const start = source.indexOf('fun dispatchInternal(');
  if (start < 0) return null;
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}' && --depth === 0) return source.slice(bodyStart, i + 1);
  }
  return null;
}

function collectHostMethods() {
  const source = readFileSync(join(root, HOST_KT), 'utf8');
  const body = dispatchInternalBody(source);
  if (!body) return { methods: new Set(), error: `未找到 dispatchInternal：${HOST_KT}` };
  const methods = new Set();
  for (const line of body.split('\n')) {
    const branch = line.match(/^\s*((?:"[A-Za-z][A-Za-z0-9_.]*"\s*,\s*)*"[A-Za-z][A-Za-z0-9_.]*")\s*->/);
    if (!branch) continue;
    for (const name of branch[1].matchAll(/"([A-Za-z][A-Za-z0-9_.]*)"/g)) methods.add(`host.${name[1]}`);
  }
  return { methods, error: null };
}

function collectDeclaredMethods() {
  const source = readFileSync(join(root, SDK_DTS), 'utf8');
  const methods = new Set();
  for (const match of source.matchAll(DTS_PATTERN)) methods.add(match[1]);
  return methods;
}

function main() {
  const { methods: hostMethods, error } = collectHostMethods();
  const declaredMethods = collectDeclaredMethods();
  const problems = [];
  if (error) problems.push(error);
  if (hostMethods.size === 0) problems.push(`未从 ${HOST_KT} 解析到任何方法，正则可能已失效`);
  if (declaredMethods.size === 0) problems.push(`未从 ${SDK_DTS} 解析到任何 HostMethodMap 键，正则可能已失效`);

  const processOnlyMethods = new Set([...hostMethods].filter((method) => method !== 'host.log.write'));
  const missingInSdk = [...processOnlyMethods].filter((m) => !declaredMethods.has(m)).sort();
  const missingInHost = [...declaredMethods].filter((m) => !hostMethods.has(m)).sort();
  if (missingInSdk.length > 0) {
    problems.push(`宿主已实现但 SDK 未声明：\n${missingInSdk.map((m) => `  - ${m}`).join('\n')}`);
  }
  if (missingInHost.length > 0) {
    problems.push(`SDK 声明但宿主没有对应分支：\n${missingInHost.map((m) => `  - ${m}`).join('\n')}`);
  }
  if (problems.length > 0) {
    console.error(`插件 host 方法表不一致（${relative(root, join(root, SDK_DTS))}）：\n`);
    for (const problem of problems) console.error(`\n${problem}`);
    process.exit(1);
  }
  console.log(`插件 host 方法表一致：宿主 ${processOnlyMethods.size} 个方法，SDK 声明 ${declaredMethods.size} 个`);
}

main();
