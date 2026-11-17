import { createInterface } from 'readline';
import {
  CoalescedEventSender,
  PluginIpcServer,
  isAsyncIterable,
  type CoalescedEventSenderOptions,
  type PluginRequestContext
} from './ipc.js';
import type {
  DeprecatedHostMethodMap,
  ExternalSessionSummary,
  HostCallArgs,
  HostMethod,
  HostMethodMap,
  HostParamsOf,
  HostResultOf,
  JsonValue,
  ToolDelegateContext,
  ToolDelegateEvent,
  WorkflowStreamEvent,
  WorkspaceItem
} from '../types/host';

// 宿主方法表与共用结构统一从 types/host.d.ts 导出，避免与页面桥两份定义各自漂移。
// 必须用 `export type *`：host.d.ts 是纯类型文件，运行时没有可解析的模块。
export type * from '../types/host';
export {
  CoalescedEventSender,
  DEFAULT_CHUNK_BYTES,
  FrameDecoder,
  FrameType,
  IPC_HEADER_BYTES,
  IPC_MAGIC,
  IPC_VERSION,
  IpcError,
  MAX_META_BYTES,
  MAX_PAYLOAD_BYTES,
  PluginIpcServer,
  binaryResult,
  encodeFrame,
  isAsyncIterable,
  isBinaryResult
} from './ipc.js';
export type {
  BinaryResult,
  CoalescedEventSenderOptions,
  CoalescedPushOptions,
  DecodedFrame,
  FrameTypeValue,
  IpcFrame,
  IpcRequestHandler,
  PluginIpcServerOptions,
  PluginRequestContext,
  StreamChunk
} from './ipc.js';

export type PluginArgs = Record<string, unknown>;

export type HostParams = Record<string, unknown>;

export const permissions = {
  request: (options: import('../types/host').PermissionRequest) => hostCall('host.permissions.request', options),
  cancel: (requestId: string) => hostCall('host.permissions.cancel', { requestId })
};

export const workspaces = {
  upsert: (backendId: string, workspaceList: WorkspaceItem[]) =>
    hostCall('host.workspaces.upsert', { backendId, workspaces: workspaceList }),
  remove: (backendId: string, workspaceIds: string[]) =>
    hostCall('host.workspaces.remove', { backendId, workspaceIds }),
  set: (backendId: string, workspaceList: WorkspaceItem[]) =>
    hostCall('host.workspaces.set', { backendId, workspaces: workspaceList })
};

export const sessions = {
  upsert: (sourceId: string, sessionList: ExternalSessionSummary[]) =>
    hostCall('host.sessions.upsert', { sourceId, sessions: sessionList }),
  remove: (sourceId: string, sessionIds: string[]) =>
    hostCall('host.sessions.remove', { sourceId, sessionIds }),
  replaceAll: (sourceId: string, sessionList: ExternalSessionSummary[]) =>
    hostCall('host.sessions.replaceAll', { sourceId, sessions: sessionList }),
  setLoading: (sourceId: string, loading: boolean) =>
    hostCall('host.sessions.setLoading', { sourceId, loading }),
  setError: (sourceId: string, error?: string | null) =>
    hostCall('host.sessions.setError', error === undefined ? { sourceId } : { sourceId, error }),
  setThinking: (sourceId: string, sessionId: string, visible: boolean, text?: string) =>
    hostCall(
      'host.sessions.setThinking',
      text === undefined ? { sourceId, sessionId, visible } : { sourceId, sessionId, visible, text }
    )
};

export type PluginHandler = (
  args: PluginArgs,
  context?: PluginRequestContext
) => unknown | Promise<unknown>;

export interface PluginDefinition {
  /** 键与插件清单中的 handler 字段一致；返回 undefined 时宿主收到 null。 */
  handlers: Record<string, PluginHandler>;
}

/** 将异步生成器注册为委托 handler；yield 进度文本，return 最终 JSON 数据。 */
export function toolDelegate(
  execute: (context: ToolDelegateContext) => AsyncGenerator<string, JsonValue, void>
): PluginHandler {
  type Execution = {
    controller: AbortController;
    iterator: AsyncGenerator<string, JsonValue, void>;
    pending?: Promise<IteratorResult<string, JsonValue>>;
    reading: boolean;
    timer: ReturnType<typeof setTimeout>;
    runId: string;
    callId: string;
    toolName: string;
  };
  const executions = new Map<string, Execution>();
  const retired = new Map<string, number>();
  function retire(id: string): void {
    retired.set(id, Date.now() + 31 * 60_000);
  }
  function release(id: string, execution: Execution): void {
    executions.delete(id);
    retire(id);
    clearTimeout(execution.timer);
    execution.controller.abort();
    void execution.iterator.return(null).catch(() => undefined);
  }
  return async (args): Promise<ToolDelegateEvent> => {
    const { executionId, runId, callId, toolName, operation } = args;
    if (typeof executionId !== 'string' || !executionId ||
        typeof runId !== 'string' || typeof callId !== 'string' || typeof toolName !== 'string') {
      throw new Error('Invalid tool delegation identity');
    }
    let execution = executions.get(executionId);
    if (operation === 'start') {
      const now = Date.now();
      for (const [id, until] of retired) {
        if (until <= now) retired.delete(id);
      }
      if (execution || retired.has(executionId)) throw new Error('Duplicate tool delegation');
      if (retired.size >= 4096) throw new Error('Too many recent tool delegations');
      if (executions.size >= 64) throw new Error('Too many tool delegations');
      if (!args.arguments || typeof args.arguments !== 'object' || Array.isArray(args.arguments)) {
        throw new Error('Invalid tool delegation arguments');
      }
      const controller = new AbortController();
      execution = {
        controller,
        iterator: execute({ executionId, runId, callId, toolName,
          arguments: args.arguments as ToolDelegateContext['arguments'], signal: controller.signal }),
        reading: false,
        timer: setTimeout(() => {
          const current = executions.get(executionId);
          if (current) release(executionId, current);
        }, 31 * 60_000),
        runId, callId, toolName
      };
      execution.timer.unref();
      executions.set(executionId, execution);
    }
    if (!execution || execution.runId !== runId || execution.callId !== callId || execution.toolName !== toolName) {
      throw new Error('Unknown tool delegation');
    }
    if (operation === 'cancel') {
      release(executionId, execution);
      return { executionId, type: 'completed', data: null };
    }
    if (operation !== 'start' && operation !== 'next') throw new Error('Invalid delegation operation');
    if (execution.reading) throw new Error('Concurrent delegation read');
    execution.reading = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      execution.pending ??= Promise.resolve().then(() => {
        if (execution!.controller.signal.aborted) throw new Error('Tool delegation cancelled');
        return execution!.iterator.next();
      });
      const result = await Promise.race([
        execution.pending,
        new Promise<null>((resolve) => { timer = setTimeout(() => resolve(null), 25_000); })
      ]);
      if (execution.controller.signal.aborted) throw new Error('Tool delegation cancelled');
      if (result === null) return { executionId, type: 'pending' };
      execution.pending = undefined;
      if (result.done) {
        executions.delete(executionId);
        retire(executionId);
        clearTimeout(execution.timer);
        return { executionId, type: 'completed', data: result.value ?? null };
      }
      if (typeof result.value !== 'string' || result.value.length > 64 * 1024) {
        throw new Error('Invalid delegation progress');
      }
      return { executionId, type: 'progress', chunk: result.value };
    } catch (error) {
      release(executionId, execution);
      return { executionId, type: 'error', message: errorMessage(error) };
    } finally {
      if (timer) clearTimeout(timer);
      execution.reading = false;
    }
  };
}

interface RpcError {
  code?: string;
  message?: string;
}

interface RpcMessage {
  jsonrpc?: string;
  id?: string | number;
  method?: string;
  params?: {
    handler?: string;
    args?: PluginArgs;
  };
  result?: unknown;
  error?: RpcError;
}

const SUPPORTED_METHODS = new Set([
  'tool.invoke',
  'hook.invoke',
  'ui.invoke',
  'backend.invoke',
  'workflow.invoke',
  'sessions.invoke'
]);

const pending = new Map<
  string,
  { resolve: (value: unknown) => void; reject: (error: Error) => void }
>();

let hostSeq = 0;
let started = false;
let ipcServer: PluginIpcServer | null = null;

function send(message: unknown): void {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function errorMessage(error: unknown): string {
  return String((error as { message?: unknown } | null)?.message || error);
}

function errorCode(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' && code ? code : 'PLUGIN_ERROR';
}

/**
 * 调用宿主能力，对应文档中的 host.* 方法。
 *
 * 方法名与入参取自 `types/host.d.ts` 的 `HostMethodMap`：
 *
 * - 不写显式泛型时，入参与返回值按方法名自动推导；
 * - 需要指定返回类型时仍可写 `hostCall<T>(...)`，此时入参归到所有方法入参的联合，
 *   仅保留“错字段 / 错类型”的检查，不校验必填字段与方法的对应关系。
 */
export function hostCall<M extends keyof HostMethodMap>(
  method: M,
  ...args: HostCallArgs<M>
): Promise<HostResultOf<M>>;

/**
 * @deprecated 该方法已废弃，仅保留在过渡期。请看本条声明对应的 `DeprecatedHostMethodMap`
 * 条目上的 `replacedBy` / `removedIn`，尽早迁到现行方法。
 *
 * 这个重载单独拆出来，就是为了让调用处能拿到 deprecated 标记：`keyof` 会丢掉属性上的
 * JSDoc，只标在 `DeprecatedHostMethodMap` 的属性上是没用的。
 */
export function hostCall<M extends keyof DeprecatedHostMethodMap>(
  method: M,
  ...args: HostCallArgs<M>
): Promise<HostResultOf<M>>;

export function hostCall<T = unknown>(
  method: HostMethod,
  ...args: [] | [params: HostParamsOf<HostMethod>]
): Promise<T>;
export function hostCall(method: string, ...args: unknown[]): Promise<unknown> {
  const params = args[0] as HostParams | undefined;
  if (ipcServer && ipcServer.isReady) {
    return ipcServer.sendRequest(method, params ?? {});
  }
  const id = 'host-' + ++hostSeq;
  return new Promise<unknown>((resolve, reject) => {
    pending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params ?? {} });
  });
}

/** 向同插件已打开的页面推送事件。 */
export function emit(name: string, payload: JsonValue = null, rawPayload?: Uint8Array): void {
  if (ipcServer && ipcServer.isReady) {
    ipcServer.sendEvent(name, payload, rawPayload);
    return;
  }
  send({ jsonrpc: '2.0', method: 'plugin.event', params: { name, payload } });
}

/** 发送工作流流式事件（非生成器 handler 或 NDJSON 回退场景）。 */
export function emitWorkflowEvent(turnId: string, event: WorkflowStreamEvent): void {
  emit('workflow.event', { turnId, ...event } as unknown as JsonValue);
}

export interface CreateEventCoalescerOptions {
  windowMs?: number;
  send?: CoalescedEventSenderOptions['send'];
  merge?: CoalescedEventSenderOptions['merge'];
}

/** 创建事件微批合并器；默认通过 `emit` 推送合并后的事件。 */
export function createEventCoalescer(options?: CreateEventCoalescerOptions): CoalescedEventSender {
  return new CoalescedEventSender({
    windowMs: options?.windowMs,
    merge: options?.merge,
    send:
      options?.send ??
      ((name, payload, rawPayload) => emit(name, payload as JsonValue, rawPayload))
  });
}

/** 写入 stderr，宿主会转入应用日志。 */
export function log(message: string): void {
  process.stderr.write('[plugin] ' + message + '\n');
}

function resolveHandlerInvocation(
  method: string,
  params: Record<string, unknown>,
  handlers: Record<string, PluginHandler>
): { handler: PluginHandler; args: PluginArgs } {
  if (typeof params.handler === 'string') {
    const handler = handlers[params.handler];
    if (typeof handler !== 'function') {
      const err = new Error(params.handler) as Error & { code?: string };
      err.code = 'HANDLER_NOT_FOUND';
      throw err;
    }
    const args =
      params.args && typeof params.args === 'object' && !Array.isArray(params.args)
        ? (params.args as PluginArgs)
        : {};
    return { handler, args };
  }

  if (SUPPORTED_METHODS.has(method)) {
    const err = new Error('') as Error & { code?: string };
    err.code = 'HANDLER_NOT_FOUND';
    throw err;
  }

  const direct = handlers[method];
  if (typeof direct === 'function') {
    return { handler: direct, args: params };
  }

  const err = new Error(method) as Error & { code?: string };
  err.code = 'METHOD_UNKNOWN';
  throw err;
}

function handleRequest(
  id: string | number,
  method: string,
  params: Record<string, unknown>,
  handlers: Record<string, PluginHandler>
): void {
  let invocation: { handler: PluginHandler; args: PluginArgs };
  try {
    invocation = resolveHandlerInvocation(method, params, handlers);
  } catch (error) {
    send({
      jsonrpc: '2.0',
      id,
      error: {
        code: errorCode(error),
        message: error instanceof Error ? error.message : errorMessage(error)
      }
    });
    return;
  }
  Promise.resolve()
    .then(() => invocation.handler(invocation.args))
    .then(async (result) => {
      if (method === 'workflow.invoke' && isAsyncIterable(result)) {
        const turnId = typeof invocation.args.turnId === 'string' ? invocation.args.turnId : '';
        const iterator = result[Symbol.asyncIterator]();
        while (true) {
          const step = await iterator.next();
          if (step.done) {
            send({
              jsonrpc: '2.0',
              id,
              result: step.value === undefined ? null : step.value
            });
            return;
          }
          const item = step.value;
          if (item !== null && typeof item === 'object' && !Array.isArray(item)) {
            emitWorkflowEvent(turnId, item as WorkflowStreamEvent);
          }
        }
      }
      send({ jsonrpc: '2.0', id, result: result === undefined ? null : result });
    })
    .catch((error) =>
      send({
        jsonrpc: '2.0',
        id,
        error: { code: errorCode(error), message: errorMessage(error) }
      })
    );
}

function handleResponse(message: RpcMessage): void {
  const id = String(message.id);
  const waiter = pending.get(id);
  if (!waiter) return;
  pending.delete(id);
  if (message.error) {
    const error = new Error(message.error.message || 'host error') as Error & { code?: string };
    error.code = message.error.code || 'HOST_ERROR';
    waiter.reject(error);
  } else {
    waiter.resolve(message.result === undefined ? null : message.result);
  }
}

/** 注册 handlers 并开始读取宿主的 NDJSON 请求；每个进程只能调用一次。 */
export function definePlugin(definition: PluginDefinition): void {
  if (started) throw new Error('definePlugin 只能调用一次');
  started = true;
  const handlers = definition.handlers;

  const socketPath = process.env.AICODE_IPC_SOCKET;
  const token = process.env.AICODE_IPC_TOKEN;
  if (socketPath && token) {
    const server = new PluginIpcServer({
      socketPath,
      token,
      onRequest: (method, params, context) => {
        const invocation = resolveHandlerInvocation(method, params, handlers);
        return invocation.handler(invocation.args, context);
      },
      onError: (error) => log('IPC 错误: ' + errorMessage(error))
    });
    ipcServer = server;
    void server
      .start()
      .then(() => {
        if (ipcServer !== server) return;
        send({ jsonrpc: '2.0', method: 'ipc.ready', params: { socket: socketPath } });
      })
      .catch((error) => {
        if (ipcServer === server) ipcServer = null;
        log('IPC 启动失败: ' + errorMessage(error));
      });
  }

  const rl = createInterface({ input: process.stdin });
  rl.on('line', (line) => {
    if (!line.trim()) return;
    let message: RpcMessage;
    try {
      message = JSON.parse(line) as RpcMessage;
    } catch {
      return;
    }
    if (message.method && message.id !== undefined) {
      handleRequest(message.id, message.method, message.params ?? {}, handlers);
    } else if (message.method) {
      return;
    } else if (message.id !== undefined) {
      handleResponse(message);
    }
  });
  rl.on('close', () => {
    if (ipcServer) {
      const server = ipcServer;
      ipcServer = null;
      void server.close().catch(() => undefined);
    }
  });
}
