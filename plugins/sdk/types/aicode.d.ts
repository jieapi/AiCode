/**
 * AiCode 插件页面宿主桥类型声明。
 *
 * 与宿主资源 `/__aicode/aicode.js` 对应：页面加载该脚本后，`window.aicode` 即为本文件描述的
 * 桥对象。本文件只含类型，没有运行时代码；通过 `@jieapi/plugin-sdk/page` 引入后生效。
 *
 * 宿主请求返回 Promise；失败时 reject 一个带 `code` 与 `message` 的 Error，见
 * {@link AicodeError}。文档：/advanced/plugin/ui-pages
 */

// ---------------------------------------------------------------------------
// 基础类型
// ---------------------------------------------------------------------------

export type JsonPrimitive = string | number | boolean | null;

/** 可 JSON 序列化的值，对应宿主与插件进程之间的数据。 */
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export type JsonObject = { [key: string]: JsonValue };

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
  // 工具
  | 'TOOL_NOT_ALLOWED'
  | 'TOOL_NOT_FOUND'
  | 'TOOL_ERROR'
  | 'TOOL_TIMEOUT'
  | 'TOOL_APPROVAL_TIMEOUT'
  | 'TOOL_USER_REJECTED'
  | 'TOOL_DENIED'
  // 网络与系统
  | 'HTTP_FAILED'
  | 'NO_ACTIVITY'
  | 'APP_INFO_UNAVAILABLE'
  // 浮层
  | 'OVERLAY_NOT_DECLARED'
  | 'OVERLAY_NOT_AUTHORIZED'
  | 'OVERLAY_SYSTEM_PERMISSION_MISSING'
  | 'OVERLAY_NOT_FOUND'
  | 'OVERLAY_DENIED'
  | 'OVERLAY_LIMIT'
  | 'OVERLAY_SERVICE_FAILED'
  | 'SURFACE_NOT_FOUND';

/** 页面主题，跟随 App 的明暗设置。 */
export type AicodeTheme = 'light' | 'dark' | (string & {});

/** 浮层的可见性策略。 */
export type OverlayVisibilityPolicy = 'always' | 'foreground' | 'background';

/** 工具审批选择。 */
export type ApprovalChoice = 'once' | 'always' | 'reject';

/** 计划审批选择。 */
export type PlanApprovalChoice = 'approve' | 'refine';

/** 新建会话可用的模式；`auto` 只能由使用者手动选择。 */
export type ChatMode = 'build' | 'plan';

/** 终端标签状态。 */
export type TerminalState = 'running' | 'finished' | 'cancelled';

/** 宿主工具执行结果状态。 */
export type ToolResultStatus = 'success' | 'error' | 'partial';

export interface AicodePageContext {
  /** 当前页面所属插件 ID。 */
  pluginId: string;
  /** 页面实例 ID；同一入口多次打开时不同。 */
  pageId: string;
  /** 页面入口的槽位，配置页为 `config`。 */
  slot?: string;
  /** 从聊天页打开的会话 ID。 */
  sessionId?: string;
  /** 从编辑器或文件树打开时的文件路径。 */
  path?: string;
  isDirectory?: boolean;
  /** 编辑器选区文本。 */
  selection?: string;
  /** 选区语言标识，如 `kotlin`、`typescript`。 */
  language?: string;
  lineStart?: number;
  lineEnd?: number;
  /** 插件版本号。 */
  version: string;
  /** 当前系统语言，如 `zh-CN`。 */
  languageTag: string;
  theme: AicodeTheme;
  workspaceName: string;
  /** 工作区标识；工作区切换后旧页面调用文件方法会得到 `STALE_CONTEXT`。 */
  contextKey: string;
  /** 页面当前是否可见。 */
  visible: boolean;
  /** 当前执行环境的 home 目录。 */
  home: string;
  /** 当前工作区根目录。 */
  workspace: string;
  /** 插件私有数据目录的真实路径，对应虚拟根 `data://`。 */
  dataDir: string;
}

export interface AicodeOverlayContext {
  pluginId: string;
  overlayId: string;
  pageId: string;
  version: string;
  density: number;
}

export type AicodeContext = AicodePageContext | AicodeOverlayContext;

/**
 * 已知事件的 payload 形状。`events.on` 的回调拿到的是通用 `AicodeEvent`；
 * 可按事件名取这里的类型断言 payload，或在调用 `on` 时显式指定泛型参数。
 */
export interface AicodeEventPayloads {
  'fs.changed': FsChangeEvent;
  'ui.lifecycle': { visible: boolean; reason: string; contextKey: string };
  'chat.run.started': { sessionId: string; startedAt: number };
  'chat.run.finished': { sessionId: string; status: string; error?: string; finishedAt: number };
}

/** 页面收到的宿主或插件事件。 */
export interface AicodeEvent<T = JsonValue> {
  name: string;
  payload: T;
}

// ---------------------------------------------------------------------------
// 文件
// ---------------------------------------------------------------------------

/** 文件方法共用的选项。 */
export interface FsOptions {
  /**
   * 调用方持有的 `context().contextKey`。工作区已经切换时请求返回 `STALE_CONTEXT`，
   * 用于拦截仍在排队的旧项目操作。
   */
  contextKey?: string;
}

export interface FsWatchOptions extends FsOptions {
  /** 递归监听子目录，默认 false。 */
  recursive?: boolean;
}

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

export interface FsChange {
  path: string;
  kind: 'created' | 'modified' | 'deleted';
}

export type FsChangeEvent = {
  subscriptionId: string;
  path: string;
} & (
  | {
      contextKey: string;
      changes: FsChange[];
      truncated: boolean;
      error?: never;
    }
  | {
      error: { code: string; message: string };
      contextKey?: never;
      changes?: never;
      truncated?: never;
    }
);

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
  params: {
    reasoningEffort?: string;
    maxOutputTokens?: number;
    temperature?: number;
  };
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

export interface OverlaySelfInfo extends OverlayInfo {
  /** 当前显示位置，单位为 dp。 */
  x: number;
  y: number;
  density: number;
  foreground: boolean;
}

export interface OverlayRect {
  left: number;
  top: number;
  width: number;
  height: number;
  topRadius?: number;
  bottomRadius?: number;
  coversBottom?: boolean;
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

export interface OverlayMessage {
  type: string;
  [key: string]: JsonValue;
}

/** 宿主注入的页面桥；页面脚本加载 `/__aicode/aicode.js` 后可用。 */
export interface AicodeBridge {
  /** 读取当前页面上下文；未使用的字段不出现。 */
  context(): Promise<AicodeContext>;

  /** 轻量 Toast 提示。 */
  toast(message: string): Promise<{ ok: boolean }>;

  /** 在对话中显示可关闭提示条；按钮点击回调同插件进程的 handler。 */
  tip(options: { title?: string; text: string; action?: { label: string; handler: string } }): Promise<{
    /** 提示条实例 ID。 */
    tipId: string;
    /** 是否已入队；宿主同一时刻只展示有限条提示。 */
    queued: boolean;
  }>;

  app: {
    /** App 是否有 Activity 处于 STARTED。 */
    isForeground(): Promise<boolean>;
    info(): Promise<AppInfo>;
  };

  ui: {
    /** 关闭插件页并回到打开前的位置。 */
    close(): Promise<{ ok: boolean }>;
    /** 用系统浏览器打开 http/https 链接。 */
    openUrl(url: string): Promise<{ ok: boolean }>;
    /** 打开或关闭页面内全屏弹层，并上报挖孔矩形。 */
    setOverlay(visible: boolean, rect?: OverlayRect): Promise<{ ok: boolean }>;
    /**
     * 接管页面内返回；handler 同步返回 true 表示已处理。
     * 解析为注销函数。
     */
    onBack(handler: () => boolean): Promise<() => void>;
  };

  /** 订阅宿主与插件进程事件，返回取消订阅函数。 */
  events: {
    on<T = JsonValue>(handler: (event: AicodeEvent<T>) => void): () => void;
  };

  /** 宿主托管的系统浮层。 */
  overlay: {
    create(options?: OverlayCreateOptions): Promise<OverlayInfo>;
    update(
      overlayId: string,
      options: {
        dx?: number;
        dy?: number;
        width?: number;
        height?: number;
        visible?: boolean;
        visibilityPolicy?: OverlayVisibilityPolicy;
      }
    ): Promise<{ ok: boolean }>;
    close(overlayId: string): Promise<{ ok: boolean }>;
    /** 浮层页面关闭自身。 */
    close(): Promise<null>;
    self(): Promise<OverlaySelfInfo>;
    /** 浮层页面上报拖拽区域，单位为 CSS 像素。 */
    setDragRegion(rect: { left: number; top: number; width: number; height: number }): Promise<void>;
    moveBy(dx: number, dy: number): Promise<void>;
    resize(
      width: number,
      height: number,
      options?: { animate?: boolean; x?: number; y?: number }
    ): Promise<{ width: number; height: number }>;
    setVisibilityPolicy(policy: OverlayVisibilityPolicy): Promise<void>;
    /** 接收宿主消息：state / click / dragStart / dragEnd / longPress / snap。 */
    onMessage(handler: (message: OverlayMessage) => void): () => void;
    /** 把交互或状态回传给插件进程；需要插件实现 overlay.message handler。 */
    postMessage(payload?: JsonValue): Promise<void>;
  };

  /** 调用同一插件常驻进程的 handler；不支持跨插件调用。 */
  invoke<T = unknown>(handler: string, args?: Record<string, unknown>): Promise<T>;

  /** 在当前工作区执行一次性命令；`timeout` 单位秒，默认 30、上限 300。 */
  bash(command: string, options?: { timeout?: number }): Promise<BashResult>;

  /** 通过 Shizuku 在 Android 系统执行命令；需插件声明范围且使用者已单独授权。 */
  shizuku: {
    exec(command: string, options?: { timeout?: number }): Promise<BashResult>;
  };

  terminal: {
    run(command: string, options?: { title?: string }): Promise<TerminalRunResult>;
    poll(tabId: string, offset: number): Promise<TerminalPollResult>;
    stop(tabId: string): Promise<{ ok: boolean }>;
    close(tabId: string): Promise<{ ok: boolean }>;
    /** `appendNewline` 默认 true。 */
    write(tabId: string, text: string, options?: { appendNewline?: boolean }): Promise<{ ok: boolean }>;
    list(): Promise<TerminalTab[]>;
  };

  chat: {
    listSessions(options?: { includeSubagents?: boolean; limit?: number }): Promise<ChatSessionListResult>;
    createSession(options?: { title?: string; mode?: ChatMode }): Promise<ChatSessionResult>;
    listMessages(
      sessionId: string,
      options?: { limit?: number; before?: number; after?: number }
    ): Promise<ChatMessageListResult>;
    /** 只表示请求已入队；需要聊天页存活。 */
    sendMessage(sessionId: string, text: string): Promise<{ sessionId: string; accepted: boolean }>;
    setDraft(
      text: string,
      options?: { sessionId?: string; append?: boolean }
    ): Promise<{ sessionId?: string; accepted: boolean }>;
    stop(sessionId: string): Promise<{ sessionId: string; accepted: boolean }>;
    openSession(sessionId: string): Promise<{ sessionId: string; accepted: boolean }>;
    getSession(sessionId: string): Promise<ChatSessionResult>;
    renameSession(sessionId: string, title: string): Promise<{ ok: boolean }>;
    pinSession(sessionId: string, pinned: boolean): Promise<{ ok: boolean }>;
    /** 提交删除请求；accepted 表示已入队。 */
    deleteSession(sessionId: string): Promise<{ sessionId: string; accepted: boolean }>;
    getRunState(sessionId: string): Promise<ChatRunStateResult>;
    waitForRun(sessionId: string, options?: { timeoutMs?: number }): Promise<ChatWaitForRunResult>;
    listApprovals(sessionId: string): Promise<ChatApprovalsResult>;
    /** `always` 仅在 `rememberablePatterns` 非空时可用。 */
    resolveToolApproval(
      sessionId: string,
      requestId: string,
      choice: ApprovalChoice
    ): Promise<{ ok: boolean }>;
    resolveQuestion(
      sessionId: string,
      requestId: string,
      answers: UserQuestionAnswer[]
    ): Promise<{ ok: boolean }>;
    resolvePlanApproval(
      sessionId: string,
      requestId: string,
      choice: PlanApprovalChoice
    ): Promise<{ ok: boolean }>;
  };

  /**
   * 维护本插件在 `contributes.providers` 中声明的提供商。
   * 只能访问本插件声明的条目；其它插件或用户自建的提供商一律按不存在处理。
   */
  providers: {
    /** 读取当前配置的模型列表；不访问上游服务。 */
    listModels(providerId: string): Promise<ProviderModelsResult>;
    /** 追加模型；重复添加视为成功，尚无选中模型时自动选中该模型。 */
    addModel(
      providerId: string,
      model: string,
      options?: { contextTokens?: number }
    ): Promise<ProviderModelsResult>;
    /** 删除模型；不存在视为成功，删除的是选中模型时回退到剩余模型。 */
    deleteModel(providerId: string, model: string): Promise<ProviderModelsResult & {
      removed: boolean;
    }>;
    /** 修改模型上下文窗口，用于自动识别不到的模型。 */
    setModelContext(
      providerId: string,
      model: string,
      contextTokens: number
    ): Promise<{ providerId: string; model: string; contextTokens: number }>;
    /** 设置插件提供商的 API Key；空串清除，旧值不会返回。 */
    setApiKey(providerId: string, apiKey: string): Promise<{
      providerId: string;
      hasKey: boolean;
    }>;
  };

  /** 模型句柄；需要清单声明 `contributes.modelAccess`。 */
  model: {
    listProviders(): Promise<{ providers: ModelProviderInfo[] }>;
    listModels(providerId: string | { providerId: string }): Promise<{
      providerId: string;
      models: ModelInfo[];
    }>;
    create(options: ModelCreateOptions): Promise<{ handle: ModelHandle }>;
    close(handleId: string | { handleId: string }): Promise<{ ok: boolean }>;
    start(options: ModelStartOptions): Promise<{ streamId: string }>;
    read(options: ModelReadOptions): Promise<ModelReadResult>;
    cancel(streamId: string | { streamId: string }): Promise<{ ok: boolean }>;
    /** `start` + `read` 循环，逐条回调事件并返回最终 read 结果。 */
    stream(
      options: Omit<ModelStartOptions, 'waitMs'> & { waitMs?: number },
      onEvent: (event: ModelStreamEvent) => void
    ): Promise<ModelReadResult>;
  };

  fs: {
    read(path: string, options?: FsOptions): Promise<FsReadResult>;
    write(path: string, content: string, options?: FsOptions): Promise<void>;
    list(path: string, options?: FsOptions): Promise<FsListResult>;
    delete(path: string, options?: FsOptions): Promise<void>;
    stat(path: string, options?: FsOptions): Promise<FsStatResult>;
    mkdir(path: string, options?: FsOptions): Promise<void>;
    /** 目标已存在时返回 `FILE_EXISTS`。 */
    rename(from: string, to: string, options?: FsOptions): Promise<void>;
    copy(from: string, to: string, options?: FsOptions & { overwrite?: boolean }): Promise<void>;
    append(path: string, content: string, options?: FsOptions): Promise<void>;
    readBinary(path: string, options?: FsOptions): Promise<FsBinaryResult>;
    writeBinary(path: string, base64: string, options?: FsOptions): Promise<void>;
    /** 订阅文件来源变化；解析为取消订阅函数。 */
    watch(
      path: string,
      options: FsWatchOptions,
      handler: (event: FsChangeEvent) => void
    ): Promise<() => Promise<void>>;
    watch(path: string, handler: (event: FsChangeEvent) => void): Promise<() => Promise<void>>;
  };

  clipboard: {
    read(): Promise<{ text: string }>;
    write(text: string): Promise<{ ok: boolean }>;
  };

  share: {
    text(text: string, options?: { title?: string }): Promise<{ ok: boolean }>;
  };

  /** 插件私有加密键值存储；值上限 64 KiB。 */
  secrets: {
    get(key: string): Promise<{ value: string }>;
    set(key: string, value: string): Promise<{ ok: boolean }>;
    delete(key: string): Promise<{ ok: boolean }>;
    list(): Promise<{ keys: string[] }>;
  };

  sessionSource: {
    setMessages(options: import('./host').HostParamsOf<'host.sessionSource.setMessages'>): Promise<{ ok: boolean }>;
    pushEvent(options: import('./host').HostParamsOf<'host.sessionSource.pushEvent'>): Promise<{ ok: boolean }>;
  };

  /** 仅本次用户决定；不会覆盖宿主权限策略或记忆授权。 */
  permissions: {
    request(options: import('./host').PermissionRequest): Promise<import('./host').PermissionDecision>;
    cancel(requestId: string): Promise<{ cancelled: boolean }>;
  };

  /** 宿主工具目录与执行；只允许内置 / MCP 工具。 */
  tools: {
    list(options?: { includeSchema?: boolean }): Promise<ToolsListResult>;
    execute(
      name: string,
      args: Record<string, unknown>,
      options?: ToolExecuteOptions
    ): Promise<ToolExecuteResult>;
    pending(sessionId?: string): Promise<ToolsPendingResult>;
    resolve(requestId: string, choice: ApprovalChoice): Promise<{ ok: boolean }>;
  };

  mcp: {
    status(): Promise<McpStatusResult>;
  };

  /** 宿主代理 HTTP 请求；不跟随重定向、不注入宿主凭据。 */
  http: {
    request(options: HttpRequestOptions): Promise<HttpResponse>;
  };

  log: {
    write(level: LogLevel, message: string, options?: { tag?: string }): Promise<{ ok: boolean }>;
  };

  /** 设置 CSS 变量并派发 `aicode:theme` 事件。 */
  applyTheme(variables: Record<string, string | number>): void;
}

/** 宿主注入的组件库全局对象（`/__aicode/aicode-ui-vue.js`）。 */
export interface AicodeUI {
  install(app: unknown): void;
  renderMarkdown(text: string): string;
  icons: Record<string, string>;
  components: Record<string, unknown>;
}

declare global {
  const aicode: AicodeBridge;
  const AicodeUI: AicodeUI;

  interface Window {
    aicode: AicodeBridge;
    AicodeUI?: AicodeUI;
  }
}
