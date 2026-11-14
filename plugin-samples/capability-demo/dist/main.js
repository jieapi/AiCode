'use strict';

const readline = require('readline');

let hostSeq = 0;
const hostPending = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params || {} });
  });
}

const handlers = {
  'tools.showTip': async (args) => {
    const actionLabel = args.actionLabel ? String(args.actionLabel) : '';
    const result = await hostCall('host.tip', {
      title: String(args.title ?? '演示提示'),
      text: String(args.text ?? '这是一条来自插件能力演示的提示条。'),
      action: actionLabel
        ? { label: actionLabel, handler: 'tools.tipAction' }
        : undefined
    });
    return result;
  },
  'tools.tipAction': () => ({ ok: true, message: '提示条按钮回调已到达插件' }),
  'tools.shizuku': async (args) => {
    const command = String(args.command ?? 'settings get global zen_mode');
    return await hostCall('host.shizuku.exec', { command, timeout: 30 });
  },
  'actions.fillDraft': async () => {
    await hostCall('host.chat.setDraft', { text: '请总结当前会话的结论和待办。' });
    return { message: '已填入输入框' };
  },
  'actions.fileInfo': async (args) => {
    const path = String(args.path || '');
    if (!/^(workspace|aicode|data|plugin):\/\//.test(path)) {
      return { message: `工作区外路径：${path}` };
    }
    const stat = await hostCall('host.fs.stat', { path });
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

function handleHostRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'hook.invoke' && method !== 'ui.invoke') {
    send({ jsonrpc: '2.0', id, error: { code: 'METHOD_UNKNOWN', message: method } });
    return;
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    send({ jsonrpc: '2.0', id, error: { code: 'HANDLER_NOT_FOUND', message: params.handler } });
    return;
  }
  Promise.resolve()
    .then(() => handler(params.args || {}))
    .then((result) => send({ jsonrpc: '2.0', id, result: result === undefined ? null : result }))
    .catch((error) => send({
      jsonrpc: '2.0',
      id,
      error: { code: 'PLUGIN_ERROR', message: String((error && error.message) || error) }
    }));
}

function handleHostResponse(id, message) {
  const pending = hostPending.get(id);
  if (!pending) return;
  hostPending.delete(id);
  if (message.error) pending.reject(new Error(message.error.message || 'host error'));
  else pending.resolve(message.result === undefined ? null : message.result);
}

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch (e) {
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
