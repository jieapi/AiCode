import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'http';
import { URL } from 'url';
import { definePlugin, hostCall, log, type PluginHandler } from '@jieapi/plugin-sdk';

const HOST = '127.0.0.1';
const PORT = 18080;
const MODEL = 'demo-echo';
const API_TOKEN = 'demo-local-token';
const MAX_BODY_BYTES = 4 * 1024 * 1024;

interface ContentPart {
  type?: string;
  text?: string;
}

type MessageContent = string | ContentPart[];

interface ChatMessage {
  role?: string;
  content?: MessageContent;
}

interface ChatCompletionRequest {
  messages?: ChatMessage[];
  model?: string;
  stream?: boolean;
}

interface CompletionUsage {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
}

interface ChatCompletionResponse {
  id: string;
  object: 'chat.completion';
  created: number;
  model: string;
  choices: Array<{
    index: number;
    message: { role: 'assistant'; content: string };
    finish_reason: 'stop';
  }>;
  usage: CompletionUsage;
}

interface ChatCompletionChunk {
  id: string;
  object: 'chat.completion.chunk';
  created: number;
  model: string;
  choices: Array<{
    index: number;
    delta: { content?: string };
    finish_reason: 'stop' | null;
  }>;
  usage?: CompletionUsage;
}

interface ServerInfo {
  ok: true;
  port: number;
  model: string;
  alreadyRunning?: boolean;
}

interface SyncProviderResult {
  modelResult?: unknown;
  apiKeyResult?: unknown;
}

let server: Server | null = null;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function sendJson(res: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (chunk: Buffer) => {
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

function textOf(content: MessageContent | undefined): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .map((part) => {
      if (typeof part === 'string') return part;
      return part && typeof part === 'object' && typeof part.text === 'string' ? part.text : '';
    })
    .join('');
}

function lastUserText(messages: ChatMessage[] | undefined): string {
  if (!Array.isArray(messages)) return '';
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message?.role === 'user') return textOf(message.content);
  }
  return '';
}

function roughTokens(text: string): number {
  return Math.max(1, Math.ceil(text.length / 4));
}

function completionId(): string {
  return 'chatcmpl-demo-' + Date.now().toString(36);
}

function handleChat(res: ServerResponse, body: ChatCompletionRequest): void {
  const created = Math.floor(Date.now() / 1000);
  const prompt = lastUserText(body.messages);
  const reply = '演示提供商回声：' + (prompt || '（没有收到用户消息）');
  const promptTokens = roughTokens(prompt);
  const completionTokens = roughTokens(reply);
  const usage: CompletionUsage = {
    prompt_tokens: promptTokens,
    completion_tokens: completionTokens,
    total_tokens: promptTokens + completionTokens
  };

  if (body.stream === true) {
    const id = completionId();
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });
    const chunks = reply.match(/[\s\S]{1,8}/g) ?? [reply];
    chunks.forEach((text) => {
      const chunk: ChatCompletionChunk = {
        id,
        object: 'chat.completion.chunk',
        created,
        model: MODEL,
        choices: [{ index: 0, delta: { content: text }, finish_reason: null }]
      };
      res.write('data: ' + JSON.stringify(chunk) + '\n\n');
    });
    const lastChunk: ChatCompletionChunk = {
      id,
      object: 'chat.completion.chunk',
      created,
      model: MODEL,
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      usage
    };
    res.write('data: ' + JSON.stringify(lastChunk) + '\n\n');
    res.write('data: [DONE]\n\n');
    res.end();
    return;
  }

  const response: ChatCompletionResponse = {
    id: completionId(),
    object: 'chat.completion',
    created,
    model: MODEL,
    choices: [
      {
        index: 0,
        message: { role: 'assistant', content: reply },
        finish_reason: 'stop'
      }
    ],
    usage
  };
  sendJson(res, 200, response);
}

function parseRequest(raw: string): ChatCompletionRequest {
  const parsed: unknown = JSON.parse(raw || '{}');
  if (!isRecord(parsed)) throw new Error('request body must be a JSON object');
  return parsed as ChatCompletionRequest;
}

function handleRequest(req: IncomingMessage, res: ServerResponse): void {
  const url = new URL(req.url ?? '/', 'http://' + HOST + ':' + PORT);

  if (url.pathname.startsWith('/v1/') && req.headers.authorization !== 'Bearer ' + API_TOKEN) {
    sendJson(res, 401, { error: { message: 'invalid api key', type: 'invalid_request_error' } });
    return;
  }

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
        try {
          handleChat(res, parseRequest(raw));
        } catch (error) {
          sendJson(res, 400, {
            error: { message: errorMessage(error), type: 'invalid_request_error' }
          });
        }
      })
      .catch((error: unknown) => {
        if (!res.headersSent) {
          sendJson(res, 400, { error: { message: errorMessage(error), type: 'invalid_request_error' } });
        }
      });
    return;
  }

  sendJson(res, 404, { error: { message: 'not found', path: url.pathname } });
}

function startServer(): ServerInfo {
  if (server) return { ok: true, alreadyRunning: true, port: PORT, model: MODEL };
  server = createServer(handleRequest);
  server.on('error', (error: Error) => {
    log('provider-demo server error: ' + error.message);
    server = null;
  });
  server.listen(PORT, HOST, () => {
    log('provider-demo listening on http://' + HOST + ':' + PORT + '/v1');
  });
  return { ok: true, port: PORT, model: MODEL };
}

/** 启动服务后把模型与访问令牌同步到宿主里本插件声明的提供商条目。 */
async function syncProvider(): Promise<SyncProviderResult> {
  const synced: SyncProviderResult = {};
  try {
    synced.modelResult = await hostCall<unknown>('host.providers.addModel', {
      providerId: 'echo',
      model: MODEL,
      contextTokens: 32768
    });
    synced.apiKeyResult = await hostCall<unknown>('host.providers.setApiKey', {
      providerId: 'echo',
      apiKey: API_TOKEN
    });
  } catch (error) {
    log('provider-demo sync provider failed: ' + errorMessage(error));
  }
  return synced;
}

const handlers = {
  'provider.started': async (): Promise<ServerInfo & SyncProviderResult> => ({
    ...startServer(),
    ...(await syncProvider())
  }),
  'provider.start': async (): Promise<ServerInfo & SyncProviderResult> => ({
    ...startServer(),
    ...(await syncProvider())
  })
} satisfies Record<string, PluginHandler>;

process.on('unhandledRejection', (error: unknown) => {
  log('provider-demo unhandled rejection: ' + errorMessage(error));
});

startServer();

definePlugin({ handlers });
