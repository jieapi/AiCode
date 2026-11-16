import { hostCall, type PluginHandler } from '@aicode/plugin-sdk';

export const tools: Record<string, PluginHandler> = {
  'tools.showTip': async (args) => {
    const actionLabel = args.actionLabel ? String(args.actionLabel) : '';
    return await hostCall('host.tip', {
      title: String(args.title ?? '演示提示'),
      text: String(args.text ?? '这是一条来自插件能力演示的提示条。'),
      action: actionLabel
        ? { label: actionLabel, handler: 'tools.tipAction' }
        : undefined
    });
  },
  'tools.tipAction': () => ({ ok: true, message: '提示条按钮回调已到达插件' }),
  'tools.shizuku': async (args) => {
    const command = String(args.command ?? 'settings get global zen_mode');
    return await hostCall('host.shizuku.exec', { command, timeout: 30 });
  }
};
