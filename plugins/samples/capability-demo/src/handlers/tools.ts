import { hostCall, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

interface ShowTipArgs extends PluginArgs {
  title?: unknown;
  text?: unknown;
  actionLabel?: unknown;
}

interface ShizukuArgs extends PluginArgs {
  command?: unknown;
}

interface TipResult {
  ok: boolean;
  id?: string;
}

interface ShizukuResult {
  output: string;
  exitCode: number | null;
  truncated: boolean;
}

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

export const tools = {
  'tools.showTip': async (rawArgs: PluginArgs): Promise<TipResult> => {
    const args = rawArgs as ShowTipArgs;
    const actionLabel = readString(args, 'actionLabel');
    return await hostCall<TipResult>('host.tip', {
      title: readString(args, 'title', '演示提示'),
      text: readString(args, 'text', '这是一条来自插件能力演示的提示条。'),
      action: actionLabel ? { label: actionLabel, handler: 'tools.tipAction' } : undefined
    });
  },
  'tools.tipAction': (): { ok: true; message: string } => ({
    ok: true,
    message: '提示条按钮回调已到达插件'
  }),
  'tools.shizuku': async (rawArgs: PluginArgs): Promise<ShizukuResult> => {
    const args = rawArgs as ShizukuArgs;
    const command = readString(args, 'command', 'settings get global zen_mode');
    return await hostCall<ShizukuResult>('host.shizuku.exec', { command, timeout: 30 });
  }
} satisfies Record<string, PluginHandler>;
