'use strict';

const http = require('http');
const readline = require('readline');

const HOST = '127.0.0.1';
const PORT = 18080;
const MODEL = 'demo-echo';
const MAX_BODY_BYTES = 4 * 1024 * 1024;

let server = null;

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function log(message) {
  console.error('[provider-demo] ' + message);
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('request body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.map((part) => {
    if (typeof part === 'string') return part;
    if (part && typeof part.text === 'string') return part.text;
    return '';
  }).join('');
}

function lastUserText(messages) {
  if (!Array.isArray(messages)) return '';
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i];
    if (message && message.role === 'user') return textOf(message.content);
  }
  return '';
}

function roughTokens(text) {
  return Math.max(1, Math.ceil(String(text || '').length / 4));
}

function completionId() {
  return 'chatcmpl-demo-' + Date.now().toString(36);
}

function handleChat(req, res, body) {
  const created = Math.floor(Date.now() / 1000);
  const prompt = lastUserText(body.messages);
  const reply = '演示提供商回声：' + (prompt || '（没有收到用户消息）');
  const promptTokens = roughTokens(prompt);
  const completionTokens = roughTokens(reply);

  if (body.stream) {
    const id = completionId();
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });
    const chunks = reply.match(/[\s\S]{1,8}/g) || [reply];
    chunks.forEach((text) => {
      res.write('data: ' + JSON.stringify({
        id,
        object: 'chat.completion.chunk',
        created,
        model: MODEL,
        choices: [{ index: 0, delta: { content: text }, finish_reason: null }]
      }) + '\n\n');
    });
    res.write('data: ' + JSON.stringify({
      id,
      object: 'chat.completion.chunk',
      created,
      model: MODEL,
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      usage: {
        prompt_tokens: promptTokens,
        completion_tokens: completionTokens,
        total_tokens: promptTokens + completionTokens
      }
    }) + '\n\n');
    res.write('data: [DONE]\n\n');
    res.end();
    return;
  }

  sendJson(res, 200, {
    id: completionId(),
    object: 'chat.completion',
    created,
    model: MODEL,
    choices: [{
      index: 0,
      message: { role: 'assistant', content: reply },
      finish_reason: 'stop'
    }],
    usage: {
      prompt_tokens: promptTokens,
      completion_tokens: completionTokens,
      total_tokens: promptTokens + completionTokens
    }
  });
}

function handleRequest(req, res) {
  const url = new URL(req.url || '/', 'http://' + HOST + ':' + PORT);

  if (req.method === 'GET' && (url.pathname === '/health' || url.pathname === '/v1/health')) {
    sendJson(res, 200, { ok: true, service: 'demo.provider', port: PORT, model: MODEL });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/v1/models') {
    sendJson(res, 200, {
      object: 'list',
      data: [{ id: MODEL, object: 'model', created: 0, owned_by: 'demo.provider' }]
    });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
    readBody(req)
      .then((raw) => {
        let body;
        try {
          body = JSON.parse(raw || '{}');
        } catch (error) {
          sendJson(res, 400, { error: { message: 'invalid json', type: 'invalid_request_error' } });
          return;
        }
        handleChat(req, res, body);
      })
      .catch((error) => {
        if (!res.headersSent) {
          sendJson(res, 400, { error: { message: String((error && error.message) || error) } });
        }
      });
    return;
  }

  sendJson(res, 404, { error: { message: 'not found', path: url.pathname } });
}

function startServer() {
  if (server) return { ok: true, alreadyRunning: true, port: PORT, model: MODEL };
  server = http.createServer(handleRequest);
  server.on('error', (error) => {
    log('server error: ' + error.message);
    server = null;
  });
  server.listen(PORT, HOST, () => log('listening on http://' + HOST + ':' + PORT + '/v1'));
  return { ok: true, port: PORT, model: MODEL };
}

const handlers = {
  'provider.started': () => startServer(),
  'provider.start': () => startServer()
};

function handleHostRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'hook.invoke') {
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

process.on('unhandledRejection', (error) => {
  log('unhandled rejection: ' + String((error && error.message) || error));
});

// 常驻进程启动后立即监听；app.started hook 与手动工具都会走幂等启动。
startServer();

readline.createInterface({ input: process.stdin }).on('line', (line) => {
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
  }
});
