import { hostCall, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

interface FileInfoArgs extends PluginArgs {
  path?: unknown;
}

interface ExplainSelectionArgs extends PluginArgs {
  selection?: unknown;
  language?: unknown;
}

interface ActionResult {
  message: string;
}

interface FsStatResult {
  exists: boolean;
  isDir: boolean;
}

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

export const actions = {
  'actions.fillDraft': async (): Promise<ActionResult> => {
    await hostCall('host.chat.setDraft', { text: '请总结当前会话的结论和待办。' });
    return { message: '已填入输入框' };
  },

  'actions.fileInfo': async (rawArgs: PluginArgs): Promise<ActionResult> => {
    const args = rawArgs as FileInfoArgs;
    const path = readString(args, 'path');
    if (!/^(workspace|aicode|data|plugin):\/\//.test(path)) {
      return { message: `工作区外路径：${path}` };
    }
    const stat = await hostCall<FsStatResult>('host.fs.stat', { path });
    const kind = stat.exists ? (stat.isDir ? '目录' : '文件') : '不存在';
    return { message: `${path} · ${kind}` };
  },

  'actions.explainSelection': async (rawArgs: PluginArgs): Promise<ActionResult> => {
    const args = rawArgs as ExplainSelectionArgs;
    const selection = readString(args, 'selection');
    if (!selection) return { message: '没有选中文本' };
    const language = readString(args, 'language');
    await hostCall('host.chat.setDraft', {
      text: '请解释这段代码：\n```' + language + '\n' + selection + '\n```'
    });
    return { message: '已填入输入框' };
  }
} satisfies Record<string, PluginHandler>;
