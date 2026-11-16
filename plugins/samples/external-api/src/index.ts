import { definePlugin, hostCall, type PluginHandler } from '@aicode/plugin-sdk';

const ENDPOINT = 'https://httpbin.org/bearer';
const SECRET_KEY = 'api-token';

interface EndpointResult {
  ok: boolean;
  status?: number;
  authenticated?: boolean;
  error?: string;
  message?: string;
}

async function readToken(): Promise<string> {
  const result = await hostCall<{ value?: string }>('host.secrets.get', { key: SECRET_KEY });
  return String(result?.value || '');
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
    let body: { authenticated?: boolean } = {};
    try {
      body = JSON.parse(String(response.body || '{}'));
    } catch {
      body = {};
    }
    return {
      ok: response.status === 200 && body.authenticated === true,
      status: response.status,
      authenticated: body.authenticated === true
    };
  } catch (error) {
    const code = (error as { code?: unknown }).code;
    return {
      ok: false,
      error: typeof code === 'string' ? code : 'HTTP_FAILED',
      message: String((error as Error).message || error)
    };
  }
}

const handlers: Record<string, PluginHandler> = {
  'tools.whoami': async () => {
    const result = await callEndpoint();
    if (result.ok) return { authenticated: true, status: result.status };
    return {
      authenticated: false,
      status: result.status === undefined ? null : result.status,
      message: result.message || 'API Key 未被接受'
    };
  },

  'actions.saveKey': async (args) => {
    const token = String(args.token || '').trim();
    if (!token) return { ok: false, message: 'API Key 不能为空' };
    await hostCall('host.secrets.set', { key: SECRET_KEY, value: token });
    return { ok: true, keys: await keyNames() };
  },

  'actions.deleteKey': async () => {
    await hostCall('host.secrets.delete', { key: SECRET_KEY });
    return { ok: true, keys: await keyNames() };
  },

  'actions.listKeys': async () => ({ ok: true, keys: await keyNames() }),

  'actions.test': async () => callEndpoint()
};

definePlugin({ handlers });
