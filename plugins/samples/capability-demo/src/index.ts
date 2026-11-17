import { definePlugin, permissions } from '@jieapi/plugin-sdk';
import { actions } from './handlers/actions';
import { tools } from './handlers/tools';

export async function requestCommandPermission(sessionId: string, requestId: string, command: string) {
  const result = await permissions.request({
    sessionId,
    requestId,
    title: '确认插件命令',
    description: '仅确认本次操作，不会改变宿主工具权限策略。',
    operation: { kind: 'shell', command },
    timeoutMs: 120_000
  });
  return result.decision;
}

definePlugin({
  handlers: { ...tools, ...actions }
});
