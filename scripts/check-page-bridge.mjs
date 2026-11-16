#!/usr/bin/env node
/**
 * 对账插件页面桥的三处实现：宿主分发表 ↔ 页面 SDK 调用点。
 *
 * 页面侧的桥方法名有四处手写、必须同步：
 *   - `presentation/PluginWebBridge.kt`        普通页面桥（方法名不带前缀）
 *   - `overlay/PluginOverlayBridge.kt`          浮层页面桥（另一套，共用同一份 aicode.js）
 *   - `assets/plugin-ui/aicode.js`              页面 SDK，`call('方法名')` 是真实转发点
 *   - `plugins/sdk/types/aicode.d.ts`           面向作者的类型声明
 *
 * 本脚本只对账前三个的**传输层方法名**：`aicode.js` 调用了但没有宿主分支 = 运行期
 * `METHOD_UNKNOWN`，是硬错误。`aicode.d.ts` 不在比对范围内——它给作者的是外观别名
 * （如 `ui.setOverlay` 对应传输层的 `ui.overlay`），方法名不是一一对应，硬套会全是误报。
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
  'app/src/main/java/com/aicode/feature/plugin/overlay/PluginOverlayBridge.kt'
];
const PAGE_SDK = 'app/src/main/assets/plugin-ui/aicode.js';

/**
 * 从 Kotlin 的 `fun dispatch(` 起按花括号配平截出 `when` 块，再抓 `"方法名" ->`。
 * 直接全文件正则会把无关 `when` 里的字符串也抓进来。
 */
function collectKotlinMethods(file) {
  const source = readFileSync(join(root, file), 'utf8');
  const start = source.indexOf('fun dispatch(');
  if (start < 0) return { methods: new Set(), error: `未找到 fun dispatch(：${file}` };

  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  let end = bodyStart;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }

  const body = source.slice(bodyStart, end + 1);
  const methods = new Set();
  for (const match of body.matchAll(/^\s*"([A-Za-z][\w.]*)"\s*->/gm)) methods.add(match[1]);
  return { methods, error: null };
}

/** 页面 SDK 的真实转发点：`call('命名空间.方法')`。 */
function collectPageSdkCalls(file) {
  const source = readFileSync(join(root, file), 'utf8');
  const calls = new Set();
  for (const match of source.matchAll(/call\(\s*'([A-Za-z][\w.]*)'/g)) calls.add(match[1]);
  return calls;
}

function main() {
  const problems = [];
  const hostMethods = new Set();

  for (const file of HOST_BRIDGES) {
    const { methods, error } = collectKotlinMethods(file);
    if (error) problems.push(error);
    if (methods.size === 0) problems.push(`未从 ${file} 解析到任何分支，正则可能已失效`);
    for (const method of methods) hostMethods.add(method);
  }

  const pageCalls = collectPageSdkCalls(PAGE_SDK);
  if (pageCalls.size === 0) problems.push(`未从 ${PAGE_SDK} 解析到任何 call(...)，正则可能已失效`);

  // 硬错误：页面 SDK 会调用，但没有任何宿主桥分支接住 → 运行期 METHOD_UNKNOWN
  const unimplemented = [...pageCalls].filter((m) => !hostMethods.has(m)).sort();
  if (unimplemented.length > 0) {
    problems.push(
      `页面 SDK 会调用但宿主没有对应分支（运行期必然 METHOD_UNKNOWN）：\n` +
        unimplemented.map((m) => `  - ${m}`).join('\n') +
        `\n  改 ${PAGE_SDK} 或补宿主 dispatch 分支`
    );
  }

  // 软提示：宿主实现了但页面 SDK 没暴露，可能是有意只给内部用，确认即可
  const unexposed = [...hostMethods].filter((m) => !pageCalls.has(m)).sort();

  if (problems.length > 0) {
    console.error('插件页面桥不一致：');
    for (const problem of problems) console.error(`\n${problem}`);
    process.exit(1);
  }

  console.log(
    `插件页面桥一致：宿主 ${hostMethods.size} 个分支，页面 SDK ${pageCalls.size} 个调用点`
  );
  if (unexposed.length > 0) {
    console.log(
      `\n提示：以下 ${unexposed.length} 个宿主分支未在页面 SDK 暴露（确认是有意为之即可）：\n` +
        unexposed.map((m) => `  - ${m}`).join('\n')
    );
  }
}

main();
