'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { buildSync } = require('esbuild');

const sample = path.resolve(__dirname, '..');
const sdkEntry = path.resolve(__dirname, '../../../sdk/src/index.ts');
const runtimeSource = buildSync({
  entryPoints: [path.join(sample, 'src/index.ts')],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node12',
  write: false,
  logLevel: 'silent',
  alias: { '@jieapi/plugin-sdk': sdkEntry }
}).outputFiles[0].text;

function loadRuntime(seed) {
  let onLine;
  let seq = 0;
  const pending = new Map();
  const files = new Map();
  const dirs = new Set(['/data', '/data/backup-export', '/data/backup-import', '/home', '/home/.aicode', '/workspace']);

  const ensureParents = (file) => {
    let dir = file.slice(0, file.lastIndexOf('/'));
    while (dir && dir !== '/') {
      dirs.add(dir);
      dir = dir.slice(0, dir.lastIndexOf('/'));
    }
  };
  const addFile = (file, content) => {
    files.set(file, Buffer.from(content));
    ensureParents(file);
  };
  const isDir = (target) => dirs.has(target) ||
    [...files.keys()].some((file) => file.startsWith(target + '/'));
  const resolve = (value) => {
    const text = String(value || '');
    if (text.startsWith('data://')) return '/data/' + text.slice('data://'.length);
    if (text === '~') return '/home';
    if (text.startsWith('~/')) return '/home/' + text.slice(2);
    if (text.startsWith('/')) return text;
    return '/workspace/' + text;
  };
  const listDir = (target) => {
    if (!isDir(target)) throw new Error('not a directory: ' + target);
    const children = new Map();
    const candidates = [...files.keys(), ...dirs];
    for (const candidate of candidates) {
      const prefix = target === '/' ? '/' : target + '/';
      if (!candidate.startsWith(prefix) || candidate === target) continue;
      const rest = candidate.slice(prefix.length);
      const name = rest.split('/')[0];
      if (!name) continue;
      const child = target === '/' ? '/' + name : target + '/' + name;
      children.set(name, {name, isDir: isDir(child), size: files.get(child)?.length || 0});
    }
    return {entries: [...children.values()]};
  };

  Object.entries(seed).forEach(([file, content]) => addFile(file, content));

  const hostCall = (method, params) => {
    if (method === 'host.fs.stat') {
      const target = resolve(params.path);
      if (files.has(target)) return {exists: true, isDir: false, size: files.get(target).length};
      return {exists: isDir(target), isDir: isDir(target), size: 0};
    }
    if (method === 'host.fs.list') return listDir(resolve(params.path));
    if (method === 'host.fs.read') {
      const target = resolve(params.path);
      const data = files.get(target);
      if (!data) throw new Error('not found: ' + target);
      return {content: data.toString('utf8'), size: data.length};
    }
    if (method === 'host.fs.readBinary') {
      const target = resolve(params.path);
      const data = files.get(target);
      if (!data) throw new Error('not found: ' + target);
      return {base64: data.toString('base64'), size: data.length};
    }
    if (method === 'host.fs.write') {
      addFile(resolve(params.path), params.content);
      return {ok: true};
    }
    if (method === 'host.fs.writeBinary') {
      addFile(resolve(params.path), Buffer.from(params.base64, 'base64'));
      return {ok: true};
    }
    if (method === 'host.fs.delete') {
      const target = resolve(params.path);
      files.delete(target);
      [...files.keys()].filter((file) => file.startsWith(target + '/')).forEach((file) => files.delete(file));
      [...dirs].filter((dir) => dir === target || dir.startsWith(target + '/')).forEach((dir) => dirs.delete(dir));
      return {ok: true};
    }
    throw new Error('unknown host method: ' + method);
  };

  const receive = (message) => onLine(JSON.stringify(message));
  vm.runInNewContext(runtimeSource, {
    require(name) {
      assert.equal(name, 'readline');
      return {createInterface: () => ({on: (_event, handler) => { onLine = handler; }})};
    },
    process: {
      stdin: {},
      stdout: {
        write(line) {
          const message = JSON.parse(line);
          if (!message.method) {
            const callback = pending.get(message.id);
            pending.delete(message.id);
            if (message.error) callback.reject(new Error(message.error.message || 'host error'));
            else callback.resolve(message.result);
            return;
          }
          queueMicrotask(() => {
            try {
              receive({id: message.id, result: hostCall(message.method, message.params)});
            } catch (error) {
              receive({id: message.id, error: {code: 'HOST_ERROR', message: String(error.message || error)}});
            }
          });
        }
      }
    }
  }, {filename: 'backup-helper/main.js'});

  return {
    files,
    dirs,
    invoke(handler, args = {}) {
      return new Promise((resolve, reject) => {
        const id = 'invoke-' + (++seq);
        pending.set(id, {resolve, reject});
        receive({id, method: 'hook.invoke', params: {handler, args}});
      });
    },
    copySection(from, to) {
      for (const [file, content] of [...files]) {
        if (!file.startsWith(from + '/')) continue;
        const target = to + '/' + file.slice(from.length + 1);
        files.set(target, Buffer.from(content));
        ensureParents(target);
      }
    }
  };
}

function seed() {
  return {
    '/home/.aicode/memory/global.md': '# Global memory\n',
    '/home/.aicode/skills/demo/SKILL.md': '---\nname: demo\n---\nDemo skill\n',
    '/home/.aicode/agents/reviewer.md': '---\nname: reviewer\n---\n',
    '/home/.aicode/mcp.json': '{"mcpServers":{"global":{}}}\n',
    '/workspace/.aicode/memory/project.md': '# Project memory\n',
    '/workspace/.aicode/skills/proj/SKILL.md': '---\nname: proj\n---\n',
    '/workspace/.aicode/agents/helper.md': '---\nname: helper\n---\n',
    '/workspace/.aicode/mcp.json': '{"mcpServers":{"project":{}}}\n'
  };
}

test('export before hook writes staging data and a manifest', async () => {
  const env = loadRuntime(seed());
  const result = await env.invoke('hooks.onExportBefore', {dir: 'data://backup-export'});

  assert.ok(result.logs.some((line) => line.includes('staged')));
  const manifest = JSON.parse(env.files.get('/data/backup-export/manifest.json').toString('utf8'));
  assert.equal(manifest.version, 1);
  assert.ok(manifest.files.some((file) => file.scope === 'global' && file.path === 'memory/global.md'));
  assert.ok(manifest.files.some((file) => file.scope === 'global' && file.path === 'mcp.json'));
  assert.ok(manifest.files.some((file) => file.scope === 'project' && file.path === '.aicode/skills/proj/SKILL.md'));
  assert.ok(manifest.files.some((file) => file.scope === 'project' && file.path === '.aicode/mcp.json'));
  assert.equal(
    env.files.get('/data/backup-export/global/memory/global.md').toString('utf8'),
    '# Global memory\n'
  );
});

test('import hook restores from the archive section but leaves host-restored MCP alone', async () => {
  const env = loadRuntime(seed());
  await env.invoke('hooks.onExportBefore', {dir: 'data://backup-export'});
  env.copySection('/data/backup-export', '/data/backup-import');

  env.files.set('/home/.aicode/memory/global.md', Buffer.from('changed'));
  env.files.set('/workspace/.aicode/skills/proj/SKILL.md', Buffer.from('changed'));
  env.files.set('/home/.aicode/mcp.json', Buffer.from('host-restored'));

  const result = await env.invoke('hooks.onImport', {
    dir: 'data://backup-import',
    stats: {mcpServers: 1}
  });

  assert.ok(result.logs.some((line) => line.includes('restored')));
  assert.equal(env.files.get('/home/.aicode/memory/global.md').toString('utf8'), '# Global memory\n');
  assert.equal(env.files.get('/workspace/.aicode/skills/proj/SKILL.md').toString('utf8'), '---\nname: proj\n---\n');
  assert.equal(env.files.get('/home/.aicode/mcp.json').toString('utf8'), 'host-restored');
});

test('import falls back to scanning the section when the manifest is missing', async () => {
  const env = loadRuntime(seed());
  await env.invoke('hooks.onExportBefore', {dir: 'data://backup-export'});
  env.copySection('/data/backup-export', '/data/backup-import');
  env.files.delete('/data/backup-import/manifest.json');
  env.files.set('/home/.aicode/memory/global.md', Buffer.from('changed'));
  env.files.set('/home/.aicode/mcp.json', Buffer.from('changed'));

  const result = await env.invoke('hooks.onImport', {
    dir: 'data://backup-import',
    stats: {mcpServers: 0}
  });

  assert.ok(result.logs.some((line) => line.includes('restored')));
  assert.equal(env.files.get('/home/.aicode/memory/global.md').toString('utf8'), '# Global memory\n');
  assert.equal(env.files.get('/home/.aicode/mcp.json').toString('utf8'), '{"mcpServers":{"global":{}}}\n');
});
