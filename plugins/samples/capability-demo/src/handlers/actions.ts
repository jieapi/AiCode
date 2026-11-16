import { hostCall, type PluginHandler } from '@aicode/plugin-sdk';

export const actions: Record<string, PluginHandler> = {
  'actions.fillDraft': async () => {
    await hostCall('host.chat.setDraft', { text: '请总结当前会话的结论和待办。' });
    return { message: '已填入输入框' };
  },
  'actions.fileInfo': async (args) => {
    const path = String(args.path || '');
    if (!/^(workspace|aicode|data|plugin):\/\//.test(path)) {
      return { message: `工作区外路径：${path}` };
    }
    const stat = await hostCall<{ exists: boolean; isDir: boolean }>('host.fs.stat', { path });
    const kind = stat.exists ? (stat.isDir ? '目录' : '文件') : '不存在';
    return { message: `${path} · ${kind}` };
  },
  'actions.explainSelection': async (args) => {
    const selection = String(args.selection || '');
    if (!selection) return { message: '没有选中文本' };
    const language = String(args.language || '');
    await hostCall('host.chat.setDraft', {
      text: '请解释这段代码：\n```' + language + '\n' + selection + '\n```'
    });
    return { message: '已填入输入框' };
  }
};
