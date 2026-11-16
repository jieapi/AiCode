import { definePlugin, hostCall, type PluginHandler } from '@aicode/plugin-sdk';

const MAX_FILE_BYTES = 512 * 1024;
const MAX_FILES = 1500;

interface BackupSource {
  scope: 'global' | 'project';
  root: string;
  entries: string[];
}

interface FileEntry {
  scope: string;
  path: string;
  size: number;
}

interface SkippedEntry {
  scope: string;
  path: string;
  reason: string;
}

interface Manifest {
  files?: Array<{ scope: string; path: string }>;
}

interface StatInfo {
  exists: boolean;
  isDir: boolean;
  size: number;
}

interface ListInfo {
  entries?: Array<{ name: string }>;
}

interface BinaryInfo {
  base64: string;
  size?: number;
}

const SOURCES: BackupSource[] = [
  { scope: 'global', root: '~/.aicode', entries: ['memory', 'skills', 'agents', 'mcp.json'] },
  {
    scope: 'project',
    root: '',
    entries: ['.aicode/memory', '.aicode/skills', '.aicode/agents', '.aicode/mcp.json']
  }
];

function join(root: string, entry: string): string {
  return root ? root + '/' + entry : entry;
}

async function stat(path: string): Promise<StatInfo> {
  try {
    return await hostCall<StatInfo>('host.fs.stat', { path });
  } catch {
    return { exists: false, isDir: false, size: 0 };
  }
}

async function collectFile(
  stagingDir: string,
  scope: string,
  root: string,
  relative: string,
  files: FileEntry[],
  skipped: SkippedEntry[]
): Promise<void> {
  const source = join(root, relative);
  const info = await stat(source);
  if (!info.exists) return;
  if (info.isDir) {
    let listing: ListInfo;
    try {
      listing = await hostCall<ListInfo>('host.fs.list', { path: source });
    } catch {
      skipped.push({ scope, path: relative, reason: 'list_failed' });
      return;
    }
    for (const entry of listing.entries || []) {
      if (files.length >= MAX_FILES) {
        skipped.push({ scope, path: relative, reason: 'file_limit' });
        return;
      }
      await collectFile(stagingDir, scope, root, relative + '/' + entry.name, files, skipped);
    }
    return;
  }
  if (Number(info.size) > MAX_FILE_BYTES) {
    skipped.push({ scope, path: relative, reason: 'too_large' });
    return;
  }
  try {
    const data = await hostCall<BinaryInfo>('host.fs.readBinary', { path: source });
    await hostCall('host.fs.writeBinary', {
      path: `${stagingDir}/${scope}/${relative}`,
      base64: data.base64
    });
    files.push({
      scope,
      path: relative,
      size: Number(data.size) || Number(info.size) || 0
    });
  } catch {
    skipped.push({ scope, path: relative, reason: 'read_failed' });
  }
}

async function collectAll(
  stagingDir: string,
  files: FileEntry[],
  skipped: SkippedEntry[]
): Promise<void> {
  for (const source of SOURCES) {
    for (const entry of source.entries) {
      if (files.length >= MAX_FILES) return;
      await collectFile(stagingDir, source.scope, source.root, entry, files, skipped);
    }
  }
}

async function readManifest(stagingDir: string): Promise<Manifest | null> {
  try {
    const file = await hostCall<{ content?: string }>('host.fs.read', {
      path: `${stagingDir}/manifest.json`
    });
    const manifest = JSON.parse(String(file.content || ''));
    if (manifest && Array.isArray(manifest.files)) return manifest as Manifest;
  } catch {
    // 兼容没有 manifest 的旧区段，下面按目录扫描。
  }
  return null;
}

async function scanSection(
  stagingDir: string,
  scope: string
): Promise<Array<{ scope: string; path: string }>> {
  const files: Array<{ scope: string; path: string }> = [];
  const walk = async (relative: string): Promise<void> => {
    const path = relative ? `${stagingDir}/${scope}/${relative}` : `${stagingDir}/${scope}`;
    const info = await stat(path);
    if (!info.exists) return;
    if (info.isDir) {
      let listing: ListInfo;
      try {
        listing = await hostCall<ListInfo>('host.fs.list', { path });
      } catch {
        return;
      }
      for (const entry of listing.entries || []) {
        await walk(relative ? relative + '/' + entry.name : entry.name);
      }
      return;
    }
    files.push({ scope, path: relative });
  };
  await walk('');
  return files;
}

async function restoreSection(
  stagingDir: string,
  manifest: Manifest | null,
  skipMcp: boolean
): Promise<{ restored: number; skipped: SkippedEntry[] }> {
  let restored = 0;
  const skipped: SkippedEntry[] = [];
  const files = manifest?.files
    ? manifest.files.map((file) => ({ scope: file.scope, path: file.path }))
    : [...(await scanSection(stagingDir, 'global')), ...(await scanSection(stagingDir, 'project'))];
  for (const file of files) {
    if (skipMcp && String(file.path || '').endsWith('mcp.json')) {
      skipped.push({ ...file, reason: 'host_restored' });
      continue;
    }
    try {
      const data = await hostCall<BinaryInfo>('host.fs.readBinary', {
        path: `${stagingDir}/${file.scope}/${file.path}`
      });
      const target = file.scope === 'global' ? `~/.aicode/${file.path}` : file.path;
      await hostCall('host.fs.writeBinary', { path: target, base64: data.base64 });
      restored += 1;
    } catch {
      skipped.push({ ...file, reason: 'restore_failed' });
    }
  }
  return { restored, skipped };
}

const handlers: Record<string, PluginHandler> = {
  'hooks.onExportBefore': async (args) => {
    const stagingDir = String(args.dir || '');
    if (!stagingDir) throw new Error('missing staging dir');
    const files: FileEntry[] = [];
    const skipped: SkippedEntry[] = [];
    await collectAll(stagingDir, files, skipped);
    await hostCall('host.fs.write', {
      path: `${stagingDir}/manifest.json`,
      content: JSON.stringify({ version: 1, createdAt: Date.now(), files, skipped }, null, 2)
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
    const stats = args.stats as { mcpServers?: unknown } | undefined;
    const skipMcp = Number(stats?.mcpServers) > 0;
    const result = await restoreSection(stagingDir, manifest, skipMcp);
    return {
      logs: [
        `restored ${result.restored} files${result.skipped.length ? `, skipped ${result.skipped.length}` : ''}`
      ]
    };
  }
};

definePlugin({ handlers });
