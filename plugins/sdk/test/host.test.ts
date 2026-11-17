import { emitWorkflowEvent, hostCall, sessions, workspaces } from '@jieapi/plugin-sdk';
import type {
  AicodeErrorCode,
  AppInfo,
  ExecutionBackendExecParams,
  ExecutionBackendFsParams,
  ExecutionBackendTerminalParams,
  ExternalSessionMessage,
  ExternalSessionPage,
  ExternalSessionSummary,
  FsReadResult,
  HostMethod,
  HostParamsOf,
  HostResultOf,
  OkResult,
  TipResult,
  WorkflowCapabilities,
  WorkflowCompactParams,
  WorkflowGenerateTitleParams,
  WorkflowHistoryMessage,
  WorkflowStreamEvent,
  WorkflowTurnCancelParams,
  WorkflowTurnRequest,
  WorkspaceItem
} from '@jieapi/plugin-sdk';

type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Assert<T extends true> = T;

// ---------------------------------------------------------------------------
// 方法表
// ---------------------------------------------------------------------------

/** 未声明的方法名不能出现在 `HostMethod` 联合里。 */
export type UnknownMethod = Assert<
  Equal<'host.fs.rad' extends HostMethod ? true : false, false>
>;

/** 返回值与宿主实现一致。 */
export type FsReadResultType = Assert<Equal<HostResultOf<'host.fs.read'>, FsReadResult>>;
export type AppInfoResultType = Assert<Equal<HostResultOf<'host.app.info'>, AppInfo>>;
export type IsForegroundResultType = Assert<
  Equal<HostResultOf<'host.app.isForeground'>, boolean>
>;
export type TipResultType = Assert<Equal<HostResultOf<'host.tip'>, TipResult>>;
export type TerminalListResultType = Assert<
  Equal<HostResultOf<'host.terminal.list'>, import('@jieapi/plugin-sdk').TerminalTab[]>
>;

/** 入参保留了宿主必需的字段与可选字段。 */
export type StatParams = Assert<Equal<HostParamsOf<'host.fs.stat'>, { path: string }>>;
export type CopyParams = Assert<
  Equal<HostParamsOf<'host.fs.copy'>, { from: string; to: string; overwrite?: boolean }>
>;
export type NoParamsMethod = Assert<Equal<HostParamsOf<'host.app.info'>, Record<string, never>>>;

/** 执行后端与工作区类型。 */
export type WorkspacesUpsertParams = Assert<
  Equal<
    HostParamsOf<'host.workspaces.upsert'>,
    { backendId: string; workspaces: WorkspaceItem[] }
  >
>;
export type WorkspacesRemoveParams = Assert<
  Equal<
    HostParamsOf<'host.workspaces.remove'>,
    { backendId: string; workspaceIds: string[] }
  >
>;
export type WorkspacesSetParams = Assert<
  Equal<
    HostParamsOf<'host.workspaces.set'>,
    { backendId: string; workspaces: WorkspaceItem[] }
  >
>;
export type WorkspacesUpsertResult = Assert<
  Equal<HostResultOf<'host.workspaces.upsert'>, OkResult>
>;
export type BackendErrorCodes = Assert<
  'BACKEND_NOT_FOUND' | 'WORKSPACE_INVALID' extends AicodeErrorCode ? true : false
>;
export type WorkflowErrorCodes = Assert<
  'WORKFLOW_NOT_FOUND' | 'WORKFLOW_FAILED' extends AicodeErrorCode ? true : false
>;
export type SessionSourceErrorCodes = Assert<
  'SESSION_SOURCE_NOT_FOUND' | 'SESSION_SOURCE_INVALID' extends AicodeErrorCode ? true : false
>;
export type SessionsUpsertParams = Assert<
  Equal<
    HostParamsOf<'host.sessions.upsert'>,
    { sourceId: string; sessions: ExternalSessionSummary[] }
  >
>;
export type SessionsRemoveParams = Assert<
  Equal<HostParamsOf<'host.sessions.remove'>, { sourceId: string; sessionIds: string[] }>
>;
export type SessionsReplaceAllParams = Assert<
  Equal<
    HostParamsOf<'host.sessions.replaceAll'>,
    { sourceId: string; sessions: ExternalSessionSummary[] }
  >
>;
export type SessionsSetLoadingParams = Assert<
  Equal<HostParamsOf<'host.sessions.setLoading'>, { sourceId: string; loading: boolean }>
>;
export type SessionsSetErrorParams = Assert<
  Equal<HostParamsOf<'host.sessions.setError'>, { sourceId: string; error?: string | null }>
>;
export type SessionsSetThinkingParams = Assert<
  Equal<
    HostParamsOf<'host.sessions.setThinking'>,
    { sourceId: string; sessionId: string; visible: boolean; text?: string }
  >
>;
export type SessionsUpsertResult = Assert<
  Equal<HostResultOf<'host.sessions.upsert'>, OkResult>
>;
export type WorkflowStreamEventTypes = Assert<
  Equal<
    WorkflowStreamEvent['type'],
    | 'assistant.delta'
    | 'reasoning.delta'
    | 'assistant.message'
    | 'tool.preparing'
    | 'tool.started'
    | 'tool.progress'
    | 'tool.finished'
    | 'retrying'
    | 'keySwitched'
    | 'contextUsage'
    | 'compaction.started'
    | 'compaction.finished'
    | 'compaction.failed'
    | 'modeChanged'
    | 'completed'
    | 'failed'
  >
>;

// ---------------------------------------------------------------------------
// 调用形态（纯类型，函数体永不执行）
// ---------------------------------------------------------------------------

async function implicitInference(): Promise<void> {
  // 返回值按方法名推导；若退化成 unknown / any，下面的赋值会报错
  const read: FsReadResult = await hostCall('host.fs.read', { path: 'a.txt' });
  const info: AppInfo = await hostCall('host.app.info');
  const foreground: boolean = await hostCall('host.app.isForeground');
  const keys: { keys: string[] } = await hostCall('host.secrets.list');

  // 无参方法可以省略 params
  void hostCall('host.app.info');

  // 工作区便捷方法与执行后端参数类型
  const item: WorkspaceItem = {
    id: 'ws-1',
    name: 'Project',
    path: '/srv/project',
    group: 'remote',
    available: true,
    capabilities: { createChild: true, delete: false, git: true }
  };
  const upsertOk: OkResult = await workspaces.upsert('ssh-main', [item]);
  const removeOk: OkResult = await workspaces.remove('ssh-main', ['ws-1']);
  const setOk: OkResult = await workspaces.set('ssh-main', [item]);
  const fsParams: ExecutionBackendFsParams = { op: 'read', path: '/srv/project/a.txt' };
  const execParams: ExecutionBackendExecParams = { command: 'ls -la', cwd: '/srv/project' };
  const termParams: ExecutionBackendTerminalParams = { op: 'open', cols: 80, rows: 24 };

  // 工作流契约类型与 emitWorkflowEvent
  const wfCaps: WorkflowCapabilities = {
    streaming: true,
    tools: true,
    compaction: true,
    titleGeneration: false
  };
  const wfHistory: WorkflowHistoryMessage[] = [
    { role: 'user', content: 'hello' },
    { role: 'assistant', content: 'hi', reasoning: 'think' }
  ];
  const wfTurnReq: WorkflowTurnRequest = {
    sessionId: 'sess-1',
    turnId: 'turn-1',
    userRequest: 'fix bug',
    mode: 'build',
    workspacePath: '/srv/project',
    providerId: 'openai',
    model: 'gpt-5',
    reasoningEffort: 'medium',
    workflowState: { step: 1 },
    recentMessages: wfHistory
  };
  const wfCancel: WorkflowTurnCancelParams = { sessionId: 'sess-1', turnId: 'turn-1' };
  const wfCompact: WorkflowCompactParams = { sessionId: 'sess-1', messages: wfHistory };
  const wfTitle: WorkflowGenerateTitleParams = { sessionId: 'sess-1', userRequest: 'fix bug' };
  const wfEvents: WorkflowStreamEvent[] = [
    { type: 'assistant.delta', text: 'hi' },
    { type: 'reasoning.delta', text: 'step' },
    { type: 'assistant.message', content: 'hi' },
    { type: 'tool.preparing', callId: 'c1', toolName: 'readFile' },
    { type: 'tool.started', callId: 'c1', toolName: 'readFile', arguments: { path: 'a.ts' } },
    { type: 'tool.progress', callId: 'c1', chunk: '50%' },
    { type: 'tool.finished', callId: 'c1', status: 'success', result: { ok: true } },
    { type: 'retrying', attempt: 1, maxRetries: 3, code: 'RATE_LIMIT' },
    { type: 'keySwitched', index: 1, total: 2 },
    { type: 'contextUsage', inputTokens: 100, outputTokens: 20 },
    { type: 'compaction.started', reason: 'threshold' },
    { type: 'compaction.finished', summary: 'done', compactedCount: 4 },
    { type: 'compaction.failed', message: 'error' },
    { type: 'modeChanged', mode: 'plan' },
    { type: 'completed', content: 'all done', usage: { input: 100, output: 20 } },
    { type: 'failed', message: 'boom', code: 'WORKFLOW_FAILED' }
  ];
  for (const evt of wfEvents) {
    emitWorkflowEvent(wfTurnReq.turnId, evt);
  }

  // 外部会话源类型与 sessions 便捷对象
  const extSession: ExternalSessionSummary = {
    sessionId: 'ext-1',
    title: 'External Task',
    status: 'idle',
    running: false,
    updatedAt: null,
    group: 'remote',
    isPinned: true,
    workflowId: 'wf-1',
    backendId: 'ssh-1',
    workspaceId: 'ws-1',
    providerId: 'openai',
    model: 'gpt-5'
  };
  const extMsg: ExternalSessionMessage = {
    id: 'msg-1',
    role: 'assistant',
    createdAt: 1700000000,
    status: 'done',
    blocks: [{ type: 'text', text: 'hello' }]
  };
  const extPage: ExternalSessionPage = {
    sessionId: 'ext-1',
    title: 'External Task',
    status: 'idle',
    messages: [extMsg],
    hasMore: false,
    nextCursor: null
  };
  const sessUpsertOk: OkResult = await sessions.upsert('src-1', [extSession]);
  const sessRemoveOk: OkResult = await sessions.remove('src-1', ['ext-1']);
  const sessReplaceOk: OkResult = await sessions.replaceAll('src-1', [extSession]);
  const sessLoadingOk: OkResult = await sessions.setLoading('src-1', true);
  const sessErrorOk: OkResult = await sessions.setError('src-1', null);
  const sessThinkingOk: OkResult = await sessions.setThinking('src-1', 'ext-1', true, 'thinking');

  void read;
  void info;
  void foreground;
  void keys;
  void upsertOk;
  void removeOk;
  void setOk;
  void fsParams;
  void execParams;
  void termParams;
  void wfCaps;
  void wfTurnReq;
  void wfCancel;
  void wfCompact;
  void wfTitle;
  void extPage;
  void sessUpsertOk;
  void sessRemoveOk;
  void sessReplaceOk;
  void sessLoadingOk;
  void sessErrorOk;
  void sessThinkingOk;
}

async function legacyExplicitTypeParam(): Promise<void> {
  // 旧写法：显式指定返回类型，仍然可用
  const partial: { content?: string } = await hostCall<{ content?: string }>('host.fs.read', {
    path: 'a.txt'
  });
  const flag: boolean = await hostCall<boolean>('host.app.isForeground');

  void partial;
  void flag;
}

function rejectedCalls(): void {
  // @ts-expect-error 未声明的方法名
  void hostCall('host.fs.rad', { path: 'a.txt' });

  // @ts-expect-error 字段类型不匹配
  void hostCall('host.fs.read', { path: 123 });

  // @ts-expect-error 字段名写错
  void hostCall('host.fs.read', { pth: 'a.txt' });

  // @ts-expect-error 未声明的方法名（显式泛型的旧写法同样受限）
  void hostCall<FsReadResult>('host.fs.rad', { path: 'a.txt' });

  const invalidExternalSession: ExternalSessionSummary = {
    sessionId: 'ext-1',
    // @ts-expect-error ExternalSessionSummary 不包含 executionMode
    executionMode: 'remote'
  };
  void invalidExternalSession;
}

void implicitInference;
void legacyExplicitTypeParam;
void rejectedCalls;
