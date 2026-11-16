#!/usr/bin/env node
/**
 * 用 plugins/schema/plugin.schema.json 校验仓库内的插件清单。
 *
 * 宿主 PluginManifestParser 是严格解析器（未知字段直接拒绝），但那是装上去才知道；
 * 这个脚本让 CI 和本地在提交前就把清单字段写错的问题暴露出来。
 *
 * 实现上不引依赖，只支持 schema 里实际用到的关键字；顺带做一次 $ref 自检，
 * 防止改了 schema 结构留下断链（编辑器里表现为整份 schema 失效）。
 *
 * 用法：node scripts/check-plugin-manifests.mjs
 * 退出码：0 全部合法；1 有清单不合法或 schema 断链。
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const SCHEMA = 'plugins/schema/plugin.schema.json';

/** 被校验的清单：仓库自己维护的那些。plugin-official 是外部仓库源码，不在此列。 */
function collectManifests() {
  const files = ['plugins/template/plugin.json'];
  const samplesDir = join(root, 'plugins/samples');
  for (const name of readdirSync(samplesDir)) {
    if (!statSync(join(samplesDir, name)).isDirectory()) continue;
    const candidate = `plugins/samples/${name}/plugin.json`;
    try {
      if (statSync(join(root, candidate)).isFile()) files.push(candidate);
    } catch {
      // 没有清单的示例目录（如纯页面 demo）跳过
    }
  }
  return files;
}

function resolveRef(ref, schema) {
  if (!ref.startsWith('#/')) return null;
  let node = schema;
  for (const part of ref.slice(2).split('/')) {
    const key = part.replace(/~1/g, '/').replace(/~0/g, '~');
    if (node == null || typeof node !== 'object' || !(key in node)) return null;
    node = node[key];
  }
  return node;
}

function describe(value) {
  if (Array.isArray(value)) return 'array';
  if (value === null) return 'null';
  return typeof value;
}

function validate(node, value, at, errors, schema) {
  if (node == null || typeof node !== 'object') return;

  if (node.$ref) {
    const target = resolveRef(node.$ref, schema);
    if (target == null) {
      errors.push(`${at}: schema 引用了不存在的定义 ${node.$ref}`);
      return;
    }
    validate(target, value, at, errors, schema);
    return;
  }

  if (node.oneOf) {
    const passes = node.oneOf.filter((branch) => {
      const branchErrors = [];
      validate(branch, value, at, branchErrors, schema);
      return branchErrors.length === 0;
    });
    if (passes.length !== 1) {
      errors.push(`${at}: 不满足 oneOf 中恰好一个分支（实际匹配 ${passes.length} 个）`);
    }
    return;
  }

  if ('const' in node && value !== node.const) {
    errors.push(`${at}: 必须等于 ${JSON.stringify(node.const)}，实际是 ${JSON.stringify(value)}`);
  }

  if (node.enum && !node.enum.includes(value)) {
    errors.push(`${at}: 取值必须是 ${node.enum.map((v) => JSON.stringify(v)).join(' | ')}，实际是 ${JSON.stringify(value)}`);
  }

  if (node.type) {
    const types = Array.isArray(node.type) ? node.type : [node.type];
    const actual = describe(value);
    const ok = types.some((type) =>
      type === 'integer' ? Number.isInteger(value) : type === actual
    );
    if (!ok) {
      errors.push(`${at}: 类型应为 ${types.join(' | ')}，实际是 ${actual}`);
      return;
    }
  }

  if (typeof value === 'string') {
    if (node.maxLength != null && value.length > node.maxLength) {
      errors.push(`${at}: 长度 ${value.length} 超过上限 ${node.maxLength}`);
    }
    if (node.minLength != null && value.length < node.minLength) {
      errors.push(`${at}: 长度 ${value.length} 小于下限 ${node.minLength}`);
    }
    if (node.pattern && !new RegExp(node.pattern).test(value)) {
      errors.push(`${at}: 不匹配模式 ${node.pattern}（实际 ${JSON.stringify(value)}）`);
    }
  }

  if (typeof value === 'number') {
    if (node.minimum != null && value < node.minimum) errors.push(`${at}: ${value} 小于下限 ${node.minimum}`);
    if (node.maximum != null && value > node.maximum) errors.push(`${at}: ${value} 超过上限 ${node.maximum}`);
  }

  if (Array.isArray(value)) {
    if (node.maxItems != null && value.length > node.maxItems) {
      errors.push(`${at}: 条数 ${value.length} 超过上限 ${node.maxItems}`);
    }
    if (node.items) value.forEach((item, i) => validate(node.items, item, `${at}[${i}]`, errors, schema));
  }

  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const keys = Object.keys(value);
    if (node.minProperties != null && keys.length < node.minProperties) {
      errors.push(`${at}: 字段数 ${keys.length} 小于下限 ${node.minProperties}`);
    }
    if (node.maxProperties != null && keys.length > node.maxProperties) {
      errors.push(`${at}: 字段数 ${keys.length} 超过上限 ${node.maxProperties}`);
    }
    for (const key of node.required ?? []) {
      if (!(key in value)) errors.push(`${at}: 缺少必填字段 ${key}`);
    }
    const patterns = Object.entries(node.patternProperties ?? {}).map(([source, child]) => [
      new RegExp(source),
      child
    ]);
    for (const [key, child] of Object.entries(value)) {
      const propertySchema = node.properties?.[key];
      if (propertySchema) {
        validate(propertySchema, child, `${at}.${key}`, errors, schema);
        continue;
      }
      const matched = patterns.filter(([regex]) => regex.test(key));
      if (matched.length > 0) {
        for (const [, patternSchema] of matched) {
          validate(patternSchema, child, `${at}.${key}`, errors, schema);
        }
        continue;
      }
      if (node.additionalProperties === false) {
        errors.push(`${at}.${key}: 未知字段（宿主解析器会直接拒绝）`);
      } else if (node.additionalProperties) {
        validate(node.additionalProperties, child, `${at}.${key}`, errors, schema);
      }
    }
  }
}

/** 自检 schema 里所有 $ref 都能解析到目标。 */
function checkRefs(schema) {
  const broken = [];
  const walk = (node, at) => {
    if (node == null || typeof node !== 'object') return;
    if (typeof node.$ref === 'string' && resolveRef(node.$ref, schema) == null) {
      broken.push(`${at}: ${node.$ref}`);
    }
    for (const [key, child] of Object.entries(node)) {
      if (key === '$ref') continue;
      walk(child, `${at}/${key}`);
    }
  };
  walk(schema, '#');
  return broken;
}

function main() {
  const schema = JSON.parse(readFileSync(join(root, SCHEMA), 'utf8'));

  const brokenRefs = checkRefs(schema);
  if (brokenRefs.length > 0) {
    console.error(`${SCHEMA} 有断链的 $ref（编辑器里会整份失效）：`);
    for (const ref of brokenRefs) console.error(`  - ${ref}`);
    process.exit(1);
  }

  const manifests = collectManifests();
  let failed = 0;

  for (const file of manifests) {
    const errors = [];
    let data;
    try {
      data = JSON.parse(readFileSync(join(root, file), 'utf8'));
    } catch (e) {
      console.error(`✗ ${file}: JSON 解析失败 — ${e.message}`);
      failed += 1;
      continue;
    }
    validate(schema, data, file, errors, schema);
    if (errors.length > 0) {
      console.error(`✗ ${file}`);
      for (const error of errors) console.error(`    ${error}`);
      failed += 1;
    }
  }

  if (failed > 0) {
    console.error(`\n${failed} / ${manifests.length} 份清单不合法`);
    process.exit(1);
  }

  console.log(`插件清单全部合法：${manifests.length} 份，schema $ref 无断链`);
}

main();
