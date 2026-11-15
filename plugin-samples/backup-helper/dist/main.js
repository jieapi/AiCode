'use strict';

const readline = require('readline');

const MAX_FILE_BYTES = 512 * 1024;
const MAX_FILES = 1500;

/**
 * 额外备份的数据来源。global 以 ~/.aicode 为根，project 以当前工作区为根；
 * 相对路径直接交给 host.fs，它会挂到当前执行环境的真实路径上。
 */
const SOURCES = [
  {scope: 'global', root: '~/.aicode', entries: ['memory', 'skills', 'agents', 'mcp.json']},
  {scope: 'project', root: '', entries: ['.aicode/memory', '.aicode/skills', '.aicode/agents', '.aicode/mcp.json']}
];

let hostSeq = 0;
const hostPending = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, {resolve, reject});
    send({jsonrpc: '2.0', id, method, params: params || {}});
  });
}

function join(root, entry) {
  return root ? root + '/' + entry : entry;
}

async function stat(path) {
  try {
    return await hostCall('host.fs.stat', {path});
  } catch (error) {
    return {exists: false, isDir: false, size: 0};
  }
}

async function collectFile(stagingDir, scope, root, relative, files, skipped) {
  const source = join(root, relative);
  const info = await stat(source);
  if (!info.exists) return;
  if (info.isDir) {
    let listing;
    try {
      listing = await hostCall('host.fs.list', {path: source});
    } catch (error) {
      skipped.push({scope, path: relative, reason: 'list_failed'});
      return;
    }
    for (const entry of listing.entries || []) {
      if (files.length >= MAX_FILES) {
        skipped.push({scope, path: relative, reason: 'file_limit'});
        return;
      }
      await collectFile(stagingDir, scope, root, relative + '/' + entry.name, files, skipped);
    }
    return;
  }
  if (Number(info.size) > MAX_FILE_BYTES) {
    skipped.push({scope, path: relative, reason: 'too_large'});
    return;
  }
  try {
    const data = await hostCall('host.fs.readBinary', {path: source});
    await hostCall('host.fs.writeBinary', {
      path: `${stagingDir}/${scope}/${relative}`,
      base64: data.base64
    });
    files.push({scope, path: relative, size: Number(data.size) || Number(info.size) || 0});
  } catch (error) {
    skipped.push({scope, path: relative, reason: 'read_failed'});
  }
}

async function collectAll(stagingDir, files, skipped) {
  for (const source of SOURCES) {
    for (const entry of source.entries) {
      if (files.length >= MAX_FILES) return;
      await collectFile(stagingDir, source.scope, source.root, entry, files, skipped);
    }
  }
}

async function readManifest(stagingDir) {
  try {
    const file = await hostCall('host.fs.read', {path: `${stagingDir}/manifest.json`});
    const manifest = JSON.parse(String(file.content || ''));
    if (manifest && Array.isArray(manifest.files)) return manifest;
  } catch (error) {
    // 兼容没有 manifest 的旧区段，下面按目录扫描。
  }
  return null;
}

async function scanSection(stagingDir, scope) {
  const files = [];
  const walk = async (relative) => {
    const path = relative
      ? `${stagingDir}/${scope}/${relative}`
      : `${stagingDir}/${scope}`;
    const info = await stat(path);
    if (!info.exists) return;
    if (info.isDir) {
      let listing;
      try {
        listing = await hostCall('host.fs.list', {path});
      } catch (error) {
        return;
      }
      for (const entry of listing.entries || []) {
        await walk(relative ? relative + '/' + entry.name : entry.name);
      }
      return;
    }
    files.push({scope, path: relative});
  };
  await walk('');
  return files;
}

async function restoreSection(stagingDir, manifest, skipMcp) {
  let restored = 0;
  const skipped = [];
  const files = manifest && manifest.files
    ? manifest.files.map((file) => ({scope: file.scope, path: file.path}))
    : [...await scanSection(stagingDir, 'global'), ...await scanSection(stagingDir, 'project')];
  for (const file of files) {
    if (skipMcp && String(file.path || '').endsWith('mcp.json')) {
      skipped.push({...file, reason: 'host_restored'});
      continue;
    }
    try {
      const data = await hostCall('host.fs.readBinary', {
        path: `${stagingDir}/${file.scope}/${file.path}`
      });
      const target = file.scope === 'global' ? `~/.aicode/${file.path}` : file.path;
      await hostCall('host.fs.writeBinary', {path: target, base64: data.base64});
      restored += 1;
    } catch (error) {
      skipped.push({...file, reason: 'restore_failed'});
    }
  }
  return {restored, skipped};
}

const handlers = {
  'hooks.onExportBefore': async (args) => {
    const stagingDir = String(args.dir || '');
    if (!stagingDir) throw new Error('missing staging dir');
    const files = [];
    const skipped = [];
    await collectAll(stagingDir, files, skipped);
    await hostCall('host.fs.write', {
      path: `${stagingDir}/manifest.json`,
      content: JSON.stringify({version: 1, createdAt: Date.now(), files, skipped}, null, 2)
    });
    return {
      logs: [
        `staged ${files.length} files${skipped.length ? `, skipped ${skipped.length}` : ''}`,
        stagingDir
      ]
    };
  },

  'hooks.onImport': async (args) => {
    const stagingDir = String(args.dir || '');
    if (!stagingDir) throw new Error('missing staging dir');
    const manifest = await readManifest(stagingDir);
    const skipMcp = Number(args.stats && args.stats.mcpServers) > 0;
    const result = await restoreSection(stagingDir, manifest, skipMcp);
    return {
      logs: [
        `restored ${result.restored} files${result.skipped.length ? `, skipped ${result.skipped.length}` : ''}`
      ]
    };
  }
};

function handleHostRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'hook.invoke' && method !== 'ui.invoke') {
    send({jsonrpc: '2.0', id, error: {code: 'METHOD_UNKNOWN', message: method}});
    return;
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    send({jsonrpc: '2.0', id, error: {code: 'HANDLER_NOT_FOUND', message: params.handler}});
    return;
  }
  Promise.resolve()
    .then(() => handler(params.args || {}))
    .then((result) => send({jsonrpc: '2.0', id, result: result === undefined ? null : result}))
    .catch((error) => send({
      jsonrpc: '2.0',
      id,
      error: {code: 'PLUGIN_ERROR', message: String((error && error.message) || error)}
    }));
}

function handleHostResponse(id, message) {
  const pending = hostPending.get(id);
  if (!pending) return;
  hostPending.delete(id);
  if (message.error) pending.reject(new Error(message.error.message || 'host error'));
  else pending.resolve(message.result === undefined ? null : message.result);
}

readline.createInterface({input: process.stdin}).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch (error) {
    return;
  }
  if (message.method && message.id !== undefined) {
    handleHostRequest(message.id, message.method, message.params || {});
  } else if (message.method) {
    return;
  } else if (message.id !== undefined) {
    handleHostResponse(String(message.id), message);
  }
});
