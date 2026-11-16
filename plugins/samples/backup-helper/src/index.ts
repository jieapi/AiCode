import { definePlugin, hostCall, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

const MAX_FILE_BYTES = 512 * 1024;
const MAX_FILES = 1500;

type BackupScope = 'global' | 'project';

interface BackupSource {
  scope: BackupScope;
  root: string;
  entries: string[];
}

interface BackupFileRef {
  scope: BackupScope;
  path: string;
}

interface FileEntry extends BackupFileRef {
  size: number;
}

interface SkippedEntry extends BackupFileRef {
  reason: string;
}

interface Manifest {
  version?: number;
  createdAt?: number;
  files?: BackupFileRef[];
  skipped?: SkippedEntry[];
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

interface ExportBeforeArgs extends PluginArgs {
  dir?: unknown;
}

interface ImportArgs extends PluginArgs {
  dir?: unknown;
  stats?: { mcpServers?: unknown };
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

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
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
  scope: BackupScope,
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
    for (const entry of listing.entries ?? []) {
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
    const parsed: unknown = JSON.parse(String(file.content ?? ''));
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      Array.isArray((parsed as Manifest).files)
    ) {
      return parsed as Manifest;
    }
  } catch {
    // 兼容没有 manifest 的旧区段，下面按目录扫描。
  }
  return null;
}

async function scanSection(stagingDir: string, scope: BackupScope): Promise<BackupFileRef[]> {
  const files: BackupFileRef[] = [];
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
      for (const entry of listing.entries ?? []) {
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
    ? manifest.files
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

const handlers = {
  'hooks.onExportBefore': async (rawArgs: PluginArgs): Promise<{ logs: string[] }> => {
    const args = rawArgs as ExportBeforeArgs;
    const stagingDir = readString(args, 'dir');
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

  'hooks.onImport': async (rawArgs: PluginArgs): Promise<{ logs: string[] }> => {
    const args = rawArgs as ImportArgs;
    const stagingDir = readString(args, 'dir');
    if (!stagingDir) throw new Error('missing staging dir');
    const manifest = await readManifest(stagingDir);
    const skipMcp = Number(args.stats?.mcpServers) > 0;
    const result = await restoreSection(stagingDir, manifest, skipMcp);
    return {
      logs: [
        `restored ${result.restored} files${result.skipped.length ? `, skipped ${result.skipped.length}` : ''}`
      ]
    };
  }
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
