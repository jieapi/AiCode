#!/usr/bin/env node
/**
 * 对账宿主进程侧方法表与插件 SDK 类型声明。
 *
 * 宿主 `PluginRuntimeManager.hostRequest` 的 `when` 分支是进程侧能力的唯一事实源；
 * `plugins/sdk/types/host.d.ts` 的 `HostMethodMap` 是插件作者看到的声明的唯一事实源。
 * 两者必须一一对应，否则会出现「文档里有、编译报错」或「调用不报错、运行期 METHOD_UNKNOWN」。
 *
 * 用法：node scripts/check-host-methods.mjs（CI 与本地推送前跑）
 * 退出码：0 一致；1 有差异或解析失败。
 */

import { readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const HOST_KT = 'app/src/main/java/com/aicode/feature/plugin/runtime/PluginRuntimeManager.kt';
const SDK_DTS = 'plugins/sdk/types/host.d.ts';

/** 抓 Kotlin `when` 分支里的 `"host.xxx" -> ...` 方法名。 */
const HOST_PATTERN = /"(host\.[A-Za-z0-9_.]+)"\s*->/g;
/** 抓 `HostMethodMap` 里的 `'host.xxx':` 键。 */
const DTS_PATTERN = /^\s*'(host\.[A-Za-z0-9_.]+)'\s*:/gm;

function collect(file, pattern) {
  const source = readFileSync(join(root, file), 'utf8');
  const found = new Set();
  for (const match of source.matchAll(pattern)) found.add(match[1]);
  return found;
}

function main() {
  const hostMethods = collect(HOST_KT, HOST_PATTERN);
  const declaredMethods = collect(SDK_DTS, DTS_PATTERN);

  const problems = [];

  if (hostMethods.size === 0) {
    problems.push(`未从 ${HOST_KT} 解析到任何 host.* 方法，正则可能已失效`);
  }
  if (declaredMethods.size === 0) {
    problems.push(`未从 ${SDK_DTS} 解析到任何 HostMethodMap 键，正则可能已失效`);
  }

  const missingInSdk = [...hostMethods].filter((m) => !declaredMethods.has(m)).sort();
  const missingInHost = [...declaredMethods].filter((m) => !hostMethods.has(m)).sort();

  if (missingInSdk.length > 0) {
    problems.push(
      `宿主已实现但 SDK 未声明（请在 ${SDK_DTS} 的 HostMethodMap 补上）：\n` +
        missingInSdk.map((m) => `  - ${m}`).join('\n')
    );
  }
  if (missingInHost.length > 0) {
    problems.push(
      `SDK 声明但宿主没有对应分支（请从 ${SDK_DTS} 删除，或补宿主实现）：\n` +
        missingInHost.map((m) => `  - ${m}`).join('\n')
    );
  }

  if (problems.length > 0) {
    console.error(`插件 host 方法表不一致（${relative(root, join(root, SDK_DTS))}）：`);
    for (const problem of problems) console.error(`\n${problem}`);
    process.exit(1);
  }

  console.log(
    `插件 host 方法表一致：宿主 ${hostMethods.size} 个方法，SDK 声明 ${declaredMethods.size} 个`
  );
}

main();
