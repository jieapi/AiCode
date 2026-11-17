/**
 * AiCode 插件**进程侧**宿主桥类型声明。
 *
 * 对应宿主 `PluginRuntimeManager.hostRequest` 的分发表：插件常驻进程（JavaScript / Python）
 * 通过 `hostCall(method, params)` 调用宿主，`method` 即本文件 {@link HostMethodMap} 的键。
 * 与页面桥（`@jieapi/plugin-sdk/page`）不是同一套：页面桥跑在 WebView 里、方法名不带前缀、
 * 能力面也不完全一致，别把两者混用。
 *
 * 本文件只含类型，没有运行时代码。**刻意不从 `./aicode` 引入类型**：那边末尾有
 * `declare global { const aicode }`，一旦被进程侧代码间接引入，子进程里会凭空多出全局
 * `aicode`，让开发者误以为页面桥在进程里可用。
 *
 * 维护：宿主 `PluginRuntimeManager` 的 `when` 分支增删方法时同步改这里，
 * `node plugins/scripts/check-host-methods.mjs` 会对账，漏改会报错。
 * 同名方法的结构与页面桥保持一致（两侧走同一批宿主 service）；`host.notify`、
 * `host.tip`、`host.ports.*` 是进程侧独有，结构以宿主实现为准。
 */

// ---------------------------------------------------------------------------
// 基础类型
// ---------------------------------------------------------------------------

export type JsonPrimitive = string | number | boolean | null;

/** 可 JSON 序列化的值，对应宿主与插件进程之间的数据。 */
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export type JsonObject = { [key: string]: JsonValue };

export interface ToolBeforeHookResult {
  decision?: 'allow' | 'deny';
  reason?: string;
  arguments?: JsonObject;
  /** 只选择执行方；宿主完成最终参数权限检查后才调用此 handler。 */
  delegate?: { handler: string };
  logs?: string[];
}

export interface ToolDelegateContext {
  executionId: string;
  runId: string;
  callId: string;
  toolName: string;
  arguments: JsonObject;
  signal: AbortSignal;
}

export type ToolDelegateEvent =
  | { executionId: string; type: 'pending' }
  | { executionId: string; type: 'progress'; chunk: string }
  | { executionId: string; type: 'completed'; data: JsonValue }
  | { executionId: string; type: 'error'; message: string };

/** 无参方法在 {@link HostMethodMap} 里的占位类型；调用时可省略 `params`。 */
export type NoParams = Record<string, never>;

/** 宿主失败时 reject 的错误；`code` 为稳定错误码，例如 `FILE_EXISTS`、`STALE_CONTEXT`。 */
export interface AicodeError extends Error {
  code: AicodeErrorCode | (string & {});
}

export type AicodeErrorCode =
  // 通用
  | 'PLUGIN_INACTIVE'
  | 'PARAM_INVALID'
  | 'PARAM_MISSING'
  | 'METHOD_UNKNOWN'
  | 'TOO_LARGE'
  | 'INTERNAL'
  | 'PAGE_CLOSED'
  | 'SERVICE_UNAVAILABLE'
  | 'RUNTIME_UNAVAILABLE'
  // 文件
  | 'NOT_FOUND'
  | 'PATH_INVALID'
  | 'PATH_DENIED'
  | 'FILE_EXISTS'
  | 'STALE_CONTEXT'
  | 'IO_ERROR'
  | 'STORAGE_FAILED'
  | 'TOO_MANY_WATCHES'
  | 'WATCH_FAILED'
  // 会话
  | 'WORKSPACE_UNAVAILABLE'
  | 'SESSION_NOT_FOUND'
  | 'SESSION_FORBIDDEN'
  | 'CHAT_UI_UNAVAILABLE'
  | 'CHAT_BUSY'
  | 'MODE_UNSUPPORTED'
  | 'APPROVAL_NOT_FOUND'
  | 'NOT_REMEMBERABLE'
  | 'SESSION_SOURCE_NOT_FOUND'
  | 'SESSION_SOURCE_INVALID'
  // 模型
  | 'MODEL_ACCESS_NOT_DECLARED'
  | 'MODEL_NOT_AVAILABLE'
  | 'MODEL_UNSUPPORTED'
  | 'MODEL_ERROR'
  | 'PROVIDER_NOT_FOUND'
  | 'PROVIDER_DISABLED'
  | 'PROVIDER_NO_KEY'
  | 'HANDLE_NOT_FOUND'
  | 'HANDLE_LIMIT'
  | 'MODEL_LIMIT'
  | 'SELF_PROVIDER_FORBIDDEN'
  | 'STREAM_NOT_FOUND'
  | 'STREAM_LIMIT'
  | 'STREAM_BUFFER_OVERFLOW'
  | 'CURSOR_INVALID'
  | 'REQUEST_TOO_LARGE'
  | 'CONTEXT_TOO_LARGE'
  | 'PARAM_UNSUPPORTED'
  | 'IMAGE_TOO_LARGE'
  | 'IMAGE_TYPE_UNSUPPORTED'
  | 'ALL_KEYS_FAILED'
  // 终端与端口
  | 'TERMINAL_NOT_FOUND'
  | 'TERMINAL_LIMIT'
  | 'PORT_INVALID'
  | 'PORT_FORWARD_FAILED'
  // 工具
  | 'TOOL_NOT_ALLOWED'
  | 'TOOL_NOT_FOUND'
  | 'TOOL_FAILED'
  | 'TOOL_TIMEOUT'
  | 'TOOL_BUSY'
  // 通知与提示
  | 'NOTIFICATION_PERMISSION_DENIED'
  | 'NOTIFICATION_DISABLED'
  | 'TIP_LIMIT'
  // 浮层
  | 'OVERLAY_NOT_DECLARED'
  | 'OVERLAY_NOT_AUTHORIZED'
  | 'OVERLAY_SYSTEM_PERMISSION_MISSING'
  | 'OVERLAY_NOT_FOUND'
  | 'OVERLAY_DENIED'
  | 'OVERLAY_LIMIT'
  | 'OVERLAY_SERVICE_FAILED'
  | 'SURFACE_NOT_FOUND';

/** 宿主确认类方法的统一返回。 */
export interface OkResult {
  ok: boolean;
}

/** 浮层的可见性策略。 */
export type OverlayVisibilityPolicy = 'always' | 'foreground' | 'background';

/** 工具审批选择。 */
export type ApprovalChoice = 'once' | 'always' | 'reject';

/** 计划审批选择。 */
export type PlanApprovalChoice = 'approve' | 'refine';

/** 新建会话可用的模式。 */
export type ChatMode = 'build' | 'plan';

/** 终端标签状态。 */
export type TerminalState = 'running' | 'finished' | 'cancelled';

/** 宿主工具执行结果状态。 */
export type ToolResultStatus = 'success' | 'error' | 'partial';

// ---------------------------------------------------------------------------
// 文件
// ---------------------------------------------------------------------------

export interface FsReadResult {
  content: string;
  size: number;
}

export interface FsBinaryResult {
  base64: string;
  size: number;
}

export interface FsEntry {
  name: string;
  isDir: boolean;
  size: number;
}

export interface FsListResult {
  entries: FsEntry[];
}

export interface FsStatResult {
  exists: boolean;
  isDir: boolean;
  size: number;
}

// ---------------------------------------------------------------------------
// 命令与终端
// ---------------------------------------------------------------------------

export interface BashResult {
  output: string;
  /** 命令无法启动时为 null。 */
  exitCode: number | null;
  truncated: boolean;
}

export interface TerminalRunResult {
  tabId: string;
  state: TerminalState;
}

export interface TerminalPollResult {
  output: string;
  /** 下次轮询应传入的偏移。 */
  offset: number;
  state: TerminalState;
  exitCode?: number;
  /** 超出宿主保留的最近 128 KiB 时为 true。 */
  truncated: boolean;
}

export interface TerminalTab {
  tabId: string;
  title: string;
  command: string;
  state: TerminalState;
}

// ---------------------------------------------------------------------------
// 会话与消息
// ---------------------------------------------------------------------------

export interface ChatSession {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  mode: ChatMode | (string & {});
  isPinned: boolean;
  parentId?: string;
  subagentType?: string;
  providerId?: string;
  model?: string;
  goal?: string;
  goalStatus?: string;
  reasoningEffort: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  lastInputTokens: number;
}

export interface ChatToolCall {
  id: string;
  name: string;
  /** 参数超过 4000 字符时转为截断字符串并带 `argumentsTruncated`。 */
  arguments: JsonObject | string;
  argumentsTruncated?: boolean;
}

export interface ChatAttachment {
  fileName: string;
  containerPath: string;
  mimeType: string;
  sizeBytes: number;
  isImage: boolean;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'tool' | (string & {});
  content: string;
  timestamp: number;
  toolName?: string;
  isError: boolean;
  isCompacted: boolean;
  isContextSummary: boolean;
  isCompactionMarker: boolean;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  /** 思考文本，最多 8000 字符。 */
  reasoning?: string;
  reasoningTruncated?: boolean;
  /** 最多 50 条。 */
  toolCalls?: ChatToolCall[];
  /** 最多 20 条。 */
  attachments?: ChatAttachment[];
}

export interface ChatRun {
  running: boolean;
  /** `running` / `success` / `error` / `cancelled` / `idle` 等。 */
  status: string;
  error?: string;
  startedAt?: number;
  finishedAt?: number;
}

export interface ChatSessionListResult {
  sessions: ChatSession[];
}

export interface ChatSessionResult {
  session: ChatSession;
}

export interface ChatMessageListResult {
  sessionId: string;
  messages: ChatMessage[];
  hasMore: boolean;
}

export interface ChatRunStateResult {
  sessionId: string;
  run: ChatRun;
}

export interface ChatWaitForRunResult {
  sessionId: string;
  timedOut: boolean;
  run: ChatRun;
}

/** 待确认的工具调用。 */
export interface ToolApprovalRequest {
  id: string;
  toolName: string;
  title: string;
  summary: string;
  details: string;
  argsPreview: string;
  sessionId: string;
  /** 非空时才能提交 `always`。 */
  rememberablePatterns: string[];
  rememberDisabledReason?: string;
}

export interface UserQuestionOption {
  label: string;
  description: string;
}

export interface UserQuestionItem {
  question: string;
  header: string;
  multiSelect: boolean;
  options: UserQuestionOption[];
}

export interface PendingUserQuestion {
  id: string;
  sessionId: string;
  questions: UserQuestionItem[];
}

export interface PlanApprovalRequest {
  id: string;
  sessionId?: string;
  reason: string;
}

export interface ChatApprovalsResult {
  sessionId: string;
  /** 没有待处理项时为 null。 */
  tool: ToolApprovalRequest | null;
  question: PendingUserQuestion | null;
  plan: PlanApprovalRequest | null;
}

/** `chat.resolveQuestion` 的答案项。 */
export interface UserQuestionAnswer {
  question: string;
  selected: string[];
  customText?: string;
}

/** `chat.sendMessage` / `stop` / `openSession` / `deleteSession` 的受理回执。 */
export interface ChatAcceptedResult {
  sessionId: string;
  accepted: boolean;
}

/** `chat.setDraft` 的受理回执。 */
export interface ChatDraftAcceptedResult {
  sessionId?: string;
  accepted: boolean;
}

/** 插件会话源推送给宿主的会话摘要；sessionId 由插件生成，宿主只保存与回传。 */
export interface SessionSourceSession {
  sessionId: string;
  title?: string;
  status?: string;
  running?: boolean;
  updatedAt?: number | null;
  hostName?: string;
  workspaceTitle?: string;
  group?: string;
  capabilities?: string[];
  /** 本插件声明的提供商局部 ID。 */
  providerId?: string;
  model?: string;
  executionMode?: 'remote';
}

export interface SessionSourceMessage {
  id: string;
  role: 'user' | 'assistant' | 'tool';
  createdAt?: number;
  status?: string;
  blocks: Array<Record<string, unknown> & {type: string}>;
}

export interface SessionSourcePage {
  sessionId?: string;
  title?: string;
  status?: string;
  messages: SessionSourceMessage[];
  hasMore?: boolean;
  nextCursor?: unknown;
}

export interface SessionSourceEvent {
  sourceId: string;
  sessionId: string;
  type: 'tool-start' | 'tool-output' | 'tool-end' | 'message' | 'turn-end';
  callId?: string;
  name?: string;
  input?: unknown;
  output?: unknown;
  isError?: boolean;
  message?: SessionSourceMessage;
  revision?: number;
}

// ---------------------------------------------------------------------------
// 模型句柄
// ---------------------------------------------------------------------------

/** 插件自己声明的提供商当前配置的模型列表。 */
export interface ProviderModelsResult {
  providerId: string;
  /** 本次操作的模型；仅 addModel / deleteModel 返回。 */
  model?: string;
  models: string[];
  selectedModel: string;
}

export interface ModelProviderInfo {
  id: string;
  name: string;
  type: string;
  enabled: boolean;
  hasKey: boolean;
  isPlugin: boolean;
  isDefault: boolean;
  defaultModel: string;
  selectedModel: string;
  modelCount: number;
}

export interface ModelInfo {
  id: string;
  displayName: string;
  contextTokens: number;
  inputTokens: number | null;
  outputTokens: number | null;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsReasoning: boolean;
  supportsCustomTemperature: boolean;
  supportsImageOutput: boolean;
  modelType: string;
  reasoningLevels?: string[];
  reasoningDefaultLevel?: string;
  inputCostUsdPerM?: number;
  outputCostUsdPerM?: number;
  cacheReadCostUsdPerM?: number;
  cacheWriteCostUsdPerM?: number;
  source: string;
}

export interface ModelHandle {
  id: string;
  providerId: string;
  model: string;
  name?: string;
  params: ModelParams;
  createdAt: number;
}

export interface ModelProviderState {
  signature?: string;
  thinkingBlocksJson?: string;
}

export interface ModelToolCall {
  id: string;
  name: string;
  /** 单条超过 64 KiB 时转为截断字符串并带 `argumentsTruncated`。 */
  arguments: JsonObject | string;
  argumentsTruncated?: boolean;
}

export interface ModelImage {
  mimeType: string;
  /** 输出图片为 base64；输入图片也可用 `path` 指定文件。 */
  base64?: string;
  path?: string;
}

export interface ModelMessage {
  role: 'user' | 'assistant' | 'tool';
  content: string;
  /** `user` 可带图片。 */
  images?: ModelImage[];
  /** `assistant` 上一轮的工具调用。 */
  toolCalls?: ModelToolCall[];
  reasoning?: string;
  providerState?: ModelProviderState;
  /** `tool` 消息必填，需匹配前面 assistant 的某个 `toolCalls[].id`。 */
  toolCallId?: string;
  /** `tool` 消息必填。 */
  name?: string;
}

export interface ModelToolDefinition {
  /** `app` 引用宿主内置 / MCP 工具，`custom` 由插件自己执行。 */
  type: 'app' | 'custom';
  name: string;
  description?: string;
  inputSchema?: JsonObject;
}

export interface ModelParams {
  reasoningEffort?: string;
  maxOutputTokens?: number;
  temperature?: number;
}

export interface ModelCreateOptions {
  providerId: string;
  model: string;
  name?: string;
  systemPrompt?: string;
  params?: ModelParams;
}

export interface ModelStartOptions {
  handleId: string;
  messages: ModelMessage[];
  tools?: ModelToolDefinition[];
  systemPrompt?: string;
  params?: ModelParams;
}

export interface ModelReadOptions {
  streamId: string;
  /** 上次读取返回的 cursor；首次省略或传 0。 */
  cursor?: number;
  maxEvents?: number;
  /** 没有新事件时的最长等待，默认 15000。 */
  waitMs?: number;
}

export type ModelStreamEvent =
  | { type: 'text'; text: string }
  | { type: 'reasoning'; text: string }
  | { type: 'toolCall'; name: string }
  | {
      type: 'retry';
      attempt: number;
      maxRetries: number;
      error: { code: string; statusCode?: number };
    }
  | { type: 'keySwitch'; index: number; total: number };

export interface ModelFinal {
  content: string;
  contentTruncated?: boolean;
  reasoning?: string;
  reasoningTruncated?: boolean;
  /** 最多 32 条。 */
  toolCalls?: ModelToolCall[];
  toolCallsTruncated?: boolean;
  providerState?: ModelProviderState;
  images?: ModelImage[];
  imageCount?: number;
  imagesTruncated?: boolean;
  stopReason?: string;
  stopDetail?: string;
  usage: {
    input: number;
    output: number;
    cachedInput: number;
    cacheCreation: number;
  };
  durationMs: number;
}

export interface ModelReadResult {
  streamId: string;
  events: ModelStreamEvent[];
  cursor: number;
  done: boolean;
  status?: 'success' | 'error' | 'cancelled';
  final?: ModelFinal;
  error?: { code: string; message: string; partialContent?: string };
}

// ---------------------------------------------------------------------------
// 工具、MCP 与网络
// ---------------------------------------------------------------------------

export interface HostToolInfo {
  name: string;
  description: string;
  /** `builtin` / `mcp` 等。 */
  type: string;
  permissionPolicy: string;
  capabilities: string[];
  /** 仅 `includeSchema` 为 true 时出现。 */
  inputSchema?: JsonObject;
}

export interface ToolsListResult {
  tools: HostToolInfo[];
}

export interface ToolExecuteResult {
  status: ToolResultStatus;
  data?: JsonValue;
  message?: string;
  code?: string;
  images?: ModelImage[];
  imagesTruncated?: boolean;
  dataTruncated?: boolean;
}

export interface ToolExecuteOptions {
  sessionId?: string;
  context?: { path?: string; selection?: string; language?: string };
  /** 默认 120000，范围 1000–600000。 */
  timeoutMs?: number;
}

export interface ToolsPendingResult {
  /** 没有待审批请求时为 null。 */
  approval: ToolApprovalRequest | null;
}

export interface McpStatusResult {
  servers: Array<{ name: string; state: string; toolCount: number; error: string | null }>;
}

export interface HttpRequestOptions {
  url: string;
  method?: 'GET' | 'HEAD' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | (string & {});
  headers?: Record<string, string>;
  body?: string;
  /** 单位秒，默认 60。 */
  timeout?: number;
}

export interface PermissionRequest {
  sessionId: string;
  requestId: string;
  title: string;
  description: string;
  operation: { kind: string; command?: string; workingDirectory?: string; [key: string]: JsonValue | undefined };
  timeoutMs?: number;
}

export interface PermissionDecision {
  decision: 'allow' | 'deny' | 'cancel';
  reason?: 'timeout';
}

export interface HttpResponse {
  status: number;
  headers: Record<string, string>;
  /** 响应体，上限 1 MiB。 */
  body: string;
}

// ---------------------------------------------------------------------------
// 系统
// ---------------------------------------------------------------------------

export interface AppInfo {
  packageName: string;
  versionName: string;
  versionCode: number;
  sdkInt: number;
  languageTag: string;
  isForeground: boolean;
}

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface OverlayInfo {
  overlayId: string;
  pluginId: string;
  width: number;
  height: number;
  /** 创建结果的位置，单位为屏幕像素。 */
  x: number;
  y: number;
  draggable: boolean;
  snap: string;
  visibilityPolicy: OverlayVisibilityPolicy;
}

export interface OverlayCreateOptions {
  /** 清单 `contributes.overlay.surfaces[].id`；省略时取第一个。 */
  surface?: string;
  width?: number;
  height?: number;
  visibilityPolicy?: OverlayVisibilityPolicy;
  x?: number;
  y?: number;
}

export interface OverlayUpdateOptions {
  overlayId: string;
  /** 相对当前位置的位移，单位为 dp。 */
  dx?: number;
  dy?: number;
  width?: number;
  height?: number;
  visible?: boolean;
  visibilityPolicy?: OverlayVisibilityPolicy;
}

/** 定位浮层：`overlayId` 与 `surface` 二选一。 */
export interface OverlayRef {
  overlayId?: string;
  surface?: string;
}

/** `host.tip` 的受理回执。 */
export interface TipResult {
  tipId: string;
  queued: boolean;
}

export interface TipOptions {
  text: string;
  title?: string;
  /** `handler` 必须是插件清单里声明的 handler。 */
  action?: { label: string; handler: string };
}

export interface NotifyOptions {
  title: string;
  body: string;
  /** 相同 key 会覆盖上一条通知。 */
  key?: string;
}

// ---------------------------------------------------------------------------
// 端口转发
// ---------------------------------------------------------------------------

/** `host.ports.*` 的映射信息。 */
export interface PortForwardInfo {
  id: string;
  /** 手机侧 `127.0.0.1` 上的端口。 */
  localPort: number;
  remoteHost: string;
  remotePort: number;
  /** 可直接打开的地址，一般为 `http://127.0.0.1:<localPort>`。 */
  url: string;
  /** 非 SSH 隧道而是直连时为 true。 */
  direct: boolean;
}

export interface PortForwardOptions {
  /** 目标端口，1–65535。 */
  port: number;
  /** 期望的手机侧端口，省略或占用时由宿主顺延。 */
  localPort?: number;
  /** 从服务器视角解析的目标主机，默认 `127.0.0.1`。 */
  host?: string;
}

// ---------------------------------------------------------------------------
// 方法表
// ---------------------------------------------------------------------------

/**
 * 进程侧可调用的宿主方法。
 *
 * 键即 `hostCall` 的第一个参数；`params` 为 `NoParams` 的方法可以省略第二个参数。
 * 新增方法必须同时改宿主 `PluginRuntimeManager` 与本表。
 */
export interface HostMethodMap {
  // 文件
  'host.fs.read': { params: { path: string }; result: FsReadResult };
  'host.fs.write': { params: { path: string; content: string }; result: OkResult };
  'host.fs.list': { params: { path: string }; result: FsListResult };
  'host.fs.delete': { params: { path: string }; result: OkResult };
  'host.fs.stat': { params: { path: string }; result: FsStatResult };
  'host.fs.mkdir': { params: { path: string }; result: OkResult };
  'host.fs.rename': { params: { from: string; to: string }; result: OkResult };
  'host.fs.copy': {
    params: { from: string; to: string; overwrite?: boolean };
    result: OkResult;
  };
  'host.fs.append': { params: { path: string; content: string }; result: OkResult };
  'host.fs.readBinary': { params: { path: string }; result: FsBinaryResult };
  'host.fs.writeBinary': { params: { path: string; base64: string }; result: OkResult };

  // App 与命令
  'host.app.isForeground': { params: NoParams; result: boolean };
  'host.app.info': { params: NoParams; result: AppInfo };
  /** `timeout` 单位秒，默认 30、上限 300。 */
  'host.bash': { params: { command: string; timeout?: number }; result: BashResult };
  'host.shizuku.exec': {
    params: { command: string; timeout?: number };
    result: BashResult;
  };
  'host.notify': { params: NotifyOptions; result: OkResult };
  'host.tip': { params: TipOptions; result: TipResult };

  // 系统浮层
  'host.overlay.create': { params: OverlayCreateOptions; result: OverlayInfo };
  'host.overlay.update': { params: OverlayUpdateOptions; result: OkResult };
  'host.overlay.close': { params: OverlayRef; result: OkResult };
  'host.overlay.postMessage': {
    /** `payload` 由宿主原样透传给浮层页面，结构由插件自行约定。 */
    params: OverlayRef & { payload?: unknown };
    result: OkResult;
  };

  // 终端
  'host.terminal.run': {
    params: { command: string; title?: string };
    result: TerminalRunResult;
  };
  'host.terminal.poll': {
    params: { tabId: string; offset: number };
    result: TerminalPollResult;
  };
  'host.terminal.stop': { params: { tabId: string }; result: OkResult };
  'host.terminal.close': { params: { tabId: string }; result: OkResult };
  /** `appendNewline` 默认 true。 */
  'host.terminal.write': {
    params: { tabId: string; text: string; appendNewline?: boolean };
    result: OkResult;
  };
  'host.terminal.list': { params: NoParams; result: TerminalTab[] };

  // 会话
  'host.chat.listSessions': {
    params: { includeSubagents?: boolean; limit?: number };
    result: ChatSessionListResult;
  };
  'host.chat.createSession': {
    params: { title?: string; mode?: ChatMode };
    result: ChatSessionResult;
  };
  'host.chat.listMessages': {
    params: { sessionId: string; limit?: number; before?: number; after?: number };
    result: ChatMessageListResult;
  };
  'host.chat.sendMessage': {
    params: { sessionId: string; text: string };
    result: ChatAcceptedResult;
  };
  'host.chat.setDraft': {
    params: { text: string; sessionId?: string; append?: boolean };
    result: ChatDraftAcceptedResult;
  };
  'host.chat.stop': { params: { sessionId: string }; result: ChatAcceptedResult };
  'host.chat.openSession': { params: { sessionId: string }; result: ChatAcceptedResult };
  'host.chat.getSession': { params: { sessionId: string }; result: ChatSessionResult };
  'host.chat.renameSession': {
    params: { sessionId: string; title: string };
    result: OkResult;
  };
  'host.chat.pinSession': {
    params: { sessionId: string; pinned: boolean };
    result: OkResult;
  };
  'host.chat.deleteSession': {
    params: { sessionId: string };
    result: ChatAcceptedResult;
  };
  'host.chat.getRunState': { params: { sessionId: string }; result: ChatRunStateResult };
  'host.chat.waitForRun': {
    params: { sessionId: string; timeoutMs?: number };
    result: ChatWaitForRunResult;
  };
  'host.chat.listApprovals': {
    params: { sessionId: string };
    result: ChatApprovalsResult;
  };
  'host.chat.resolveToolApproval': {
    params: { sessionId: string; requestId: string; choice: ApprovalChoice };
    result: OkResult;
  };
  'host.chat.resolveQuestion': {
    params: { sessionId: string; requestId: string; answers: UserQuestionAnswer[] };
    result: OkResult;
  };
  'host.chat.resolvePlanApproval': {
    params: { sessionId: string; requestId: string; choice: PlanApprovalChoice };
    result: OkResult;
  };

  // 插件会话源（需清单声明 contributes.sessionSources）
  'host.sessionSource.setSessions': {
    params: { sourceId: string; sessions: SessionSourceSession[] };
    result: OkResult;
  };
  'host.sessionSource.setMessages': {
    params: {sourceId: string; sessionId: string; page: SessionSourcePage; revision?: number};
    result: OkResult;
  };
  'host.sessionSource.pushEvent': {
    params: SessionSourceEvent;
    result: OkResult;
  };
  'host.sessionSource.setLoading': {
    params: { sourceId: string; loading: boolean };
    result: OkResult;
  };
  'host.sessionSource.setError': {
    params: { sourceId: string; error?: string | null };
    result: OkResult;
  };
  /** 设置当前插件会话的思考气泡；visible=false 时关闭。 */
  'host.sessionSource.setThinking': {
    params: { sourceId: string; sessionId: string; visible: boolean; text?: string };
    result: OkResult;
  };

  // 模型句柄（需清单声明 contributes.modelAccess）
  'host.model.listProviders': {
    params: NoParams;
    result: { providers: ModelProviderInfo[] };
  };
  'host.model.listModels': {
    params: { providerId: string };
    result: { providerId: string; models: ModelInfo[] };
  };
  'host.model.create': { params: ModelCreateOptions; result: { handle: ModelHandle } };
  'host.model.close': { params: { handleId: string }; result: OkResult };
  'host.model.start': { params: ModelStartOptions; result: { streamId: string } };
  'host.model.read': { params: ModelReadOptions; result: ModelReadResult };
  'host.model.cancel': { params: { streamId: string }; result: OkResult };

  // 本插件声明的提供商
  'host.providers.listModels': {
    params: { providerId: string };
    result: ProviderModelsResult;
  };
  'host.providers.addModel': {
    params: { providerId: string; model: string; contextTokens?: number };
    result: ProviderModelsResult;
  };
  'host.providers.deleteModel': {
    params: { providerId: string; model: string };
    result: ProviderModelsResult & { removed: boolean };
  };
  'host.providers.setModelContext': {
    params: { providerId: string; model: string; contextTokens: number };
    result: { providerId: string; model: string; contextTokens: number };
  };
  'host.providers.setApiKey': {
    params: { providerId: string; apiKey: string };
    result: { providerId: string; hasKey: boolean };
  };

  // 剪贴板、分享与打开链接
  'host.clipboard.read': { params: NoParams; result: { text: string } };
  'host.clipboard.write': { params: { text: string }; result: OkResult };
  'host.share.text': { params: { text: string; title?: string }; result: OkResult };
  'host.ui.openUrl': { params: { url: string }; result: OkResult };

  // 日志
  'host.log': {
    params: { level: LogLevel; message: string; tag?: string };
    result: OkResult;
  };

  // 私有加密键值存储；值上限 64 KiB
  'host.secrets.get': { params: { key: string }; result: { value: string } };
  'host.secrets.set': { params: { key: string; value: string }; result: OkResult };
  'host.secrets.delete': { params: { key: string }; result: OkResult };
  'host.secrets.list': { params: NoParams; result: { keys: string[] } };

  // 宿主工具目录与执行；只允许内置 / MCP 工具
  'host.tools.list': {
    params: { includeSchema?: boolean };
    result: ToolsListResult;
  };
  'host.permissions.request': { params: PermissionRequest; result: PermissionDecision };
  'host.permissions.cancel': { params: { requestId: string }; result: { cancelled: boolean } };
  'host.tools.execute': {
    params: {
      name: string;
      arguments?: Record<string, unknown>;
    } & ToolExecuteOptions;
    result: ToolExecuteResult;
  };
  'host.tools.pending': { params: { sessionId?: string }; result: ToolsPendingResult };
  'host.tools.resolve': {
    params: { requestId: string; choice: ApprovalChoice };
    result: OkResult;
  };
  'host.mcp.status': { params: NoParams; result: McpStatusResult };

  // 网络与端口
  'host.http.request': { params: HttpRequestOptions; result: HttpResponse };
  'host.ports.forward': { params: PortForwardOptions; result: PortForwardInfo };
  'host.ports.close': { params: { id: string }; result: { closed: boolean } };
  'host.ports.list': { params: NoParams; result: PortForwardInfo[] };
}

/**
 * 已废弃、但仍处于过渡期的宿主方法。
 *
 * 宿主删方法时**不要直接从 `HostMethodMap` 里删**：插件是独立发版、已装插件不会重新编译，
 * 直接删会让存量 author 只看到一句 `No overload matches this call`。正确做法是逐层搬家：
 *
 * 1. 从 `HostMethodMap` 搬到本表，用 `DeprecatedHostMethodEntry` 填齐 `replacedBy` / `removedIn`；
 * 2. 宿主 `PluginRuntimeManager` 暂时保留该 `host.*` 分支（行为可以转调新方法）；
 * 3. 到下一个小版本再删宿主分支与本表条目——这一步才是真不兼容的。
 *
 * 调用本表的方法照常能编译、类型仍正确推导，但编辑器会在调用处标删除线，悬停能看到
 * `replacedBy` / `removedIn`。这是有意为之：不阻断存量插件，但不会让新代码继续用。
 *
 * 注意：删除线靠“重载函数带 @deprecated”生效，不是靠给属性加 JSDoc——`keyof` 会丢掉
 * 符号级标记，文案里的补全列表不会显示弃用样式。
 */
export interface DeprecatedHostMethodMap {
  // 当前没有废弃方法。新增条目长这样（保留注释作模板，别直接放开）：
  // 'host.fs.readText': DeprecatedHostMethodEntry<{ path: string }, FsReadResult>;
}

/** 废弃方法的元数据；两项都必填，逼着写清楚迁移路径。 */
export interface DeprecatedMethodMeta {
  /**
   * 取代它的现行方法；确实没有替代品时写 `null`。
   *
   * 故意用 `keyof HostMethodMap` 而非 `HostMethod`：一是避免 `HostMethod` →
   * `AllHostMethodMap` → 本表 → `HostMethod` 的循环引用，二是禁止“废弃方法指向
   * 另一个废弃方法”这种迁不完的链。
   */
  replacedBy: keyof HostMethodMap | null;
  /** 计划移除的宿主 API 版本，例如 `1.4.0`。 */
  removedIn: string;
}

/** 废弃方法的条目形状。 */
export type DeprecatedHostMethodEntry<P, R> = { params: P; result: R } & DeprecatedMethodMeta;

/** 现行与废弃方法的合集，派生类型统一基于它。 */
export type AllHostMethodMap = HostMethodMap & DeprecatedHostMethodMap;

/** 可调用的宿主方法名（含过渡期的废弃方法）。 */
export type HostMethod = keyof AllHostMethodMap;

/** 指定方法的入参类型。 */
export type HostParamsOf<M extends HostMethod> = AllHostMethodMap[M]['params'];

/** 指定方法的返回类型。 */
export type HostResultOf<M extends HostMethod> = AllHostMethodMap[M]['result'];

/**
 * 调用 `hostCall` 时第二个参数的元组形状：`NoParams` 方法可省略，其余必须传。
 * 供 `hostCall` 的重载签名使用。
 */
export type HostCallArgs<M extends HostMethod> = [HostParamsOf<M>] extends [NoParams]
  ? [params?: HostParamsOf<M>]
  : [params: HostParamsOf<M>];
