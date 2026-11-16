import { definePlugin, hostCall, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

const ENDPOINT = 'https://httpbin.org/bearer';
const SECRET_KEY = 'api-token';

interface EndpointResult {
  ok: boolean;
  status?: number;
  authenticated?: boolean;
  error?: string;
  message?: string;
}

interface KeyListResult {
  ok: true;
  keys: string[];
}

interface SaveKeyArgs extends PluginArgs {
  token?: unknown;
}

interface BearerResponse {
  authenticated?: boolean;
}

function errorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' && code ? code : undefined;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

async function readToken(): Promise<string> {
  const result = await hostCall<{ value?: string }>('host.secrets.get', { key: SECRET_KEY });
  return String(result?.value ?? '');
}

async function keyNames(): Promise<string[]> {
  const result = await hostCall<{ keys?: string[] }>('host.secrets.list');
  return Array.isArray(result?.keys) ? result.keys : [];
}

async function callEndpoint(): Promise<EndpointResult> {
  const token = await readToken();
  if (!token) {
    return { ok: false, error: 'NO_TOKEN', message: '还没有保存 API Key，先在配置页保存一条。' };
  }
  try {
    const response = await hostCall<{ status: number; body?: string }>('host.http.request', {
      url: ENDPOINT,
      headers: { Authorization: 'Bearer ' + token },
      timeout: 20
    });
    let body: BearerResponse = {};
    try {
      const parsed: unknown = JSON.parse(String(response.body ?? '{}'));
      if (typeof parsed === 'object' && parsed !== null) body = parsed as BearerResponse;
    } catch {
      body = {};
    }
    return {
      ok: response.status === 200 && body.authenticated === true,
      status: response.status,
      authenticated: body.authenticated === true
    };
  } catch (error) {
    const code = errorCode(error);
    return {
      ok: false,
      error: code ?? 'HTTP_FAILED',
      message: errorMessage(error)
    };
  }
}

const handlers = {
  'tools.whoami': async (): Promise<{ authenticated: boolean; status?: number | null; message?: string }> => {
    const result = await callEndpoint();
    if (result.ok) return { authenticated: true, status: result.status };
    return {
      authenticated: false,
      status: result.status ?? null,
      message: result.message ?? 'API Key 未被接受'
    };
  },

  'actions.saveKey': async (rawArgs: PluginArgs): Promise<KeyListResult | { ok: false; message: string }> => {
    const args = rawArgs as SaveKeyArgs;
    const token = readString(args, 'token').trim();
    if (!token) return { ok: false, message: 'API Key 不能为空' };
    await hostCall('host.secrets.set', { key: SECRET_KEY, value: token });
    return { ok: true, keys: await keyNames() };
  },

  'actions.deleteKey': async (): Promise<KeyListResult> => {
    await hostCall('host.secrets.delete', { key: SECRET_KEY });
    return { ok: true, keys: await keyNames() };
  },

  'actions.listKeys': async (): Promise<KeyListResult> => ({ ok: true, keys: await keyNames() }),

  'actions.test': async (): Promise<EndpointResult> => callEndpoint()
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
