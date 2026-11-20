import type {
  AicodeBridge,
  AicodeContext,
  AicodeErrorCode,
  AicodeOverlayContext,
  AicodePageContext,
  FsChange,
  FsChangeEvent,
  ModelInfo,
  OverlayInfo,
  OverlaySelfInfo
} from '@jieapi/plugin-sdk/page';
import '@jieapi/plugin-sdk/page/vue';
import type { GlobalComponents } from 'vue';

type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Assert<T extends true> = T;

type Overlay = AicodeBridge['overlay'];

export type ContainerInitializationErrorCode = Assert<
  'CONTAINER_NOT_INITIALIZED' extends AicodeErrorCode ? true : false
>;
export type ModelInputTokens = Assert<Equal<ModelInfo['inputTokens'], number | null>>;
export type ModelOutputTokens = Assert<Equal<ModelInfo['outputTokens'], number | null>>;
export type ContextResult = Assert<Equal<
  Awaited<ReturnType<AicodeBridge['context']>>,
  AicodePageContext | AicodeOverlayContext
>>;
export type DeleteResult = Assert<Equal<
  Awaited<ReturnType<AicodeBridge['chat']['deleteSession']>>,
  { sessionId: string; accepted: boolean }
>>;
export type OverlayCreateResult = Assert<Equal<Awaited<ReturnType<Overlay['create']>>, OverlayInfo>>;
export type OverlaySelfResult = Assert<Equal<Awaited<ReturnType<Overlay['self']>>, OverlaySelfInfo>>;
export type OverlayCreateDensity = Assert<Equal<'density' extends keyof OverlayInfo ? true : false, false>>;
export type OverlayDensity = Assert<Equal<OverlaySelfInfo['density'], number>>;
export type OverlayForeground = Assert<Equal<OverlaySelfInfo['foreground'], boolean>>;
export type BackRegistration = Assert<Equal<
  ReturnType<AicodeBridge['ui']['onBack']>,
  Promise<() => void>
>>;

export function readContext(context: AicodeContext): string {
  if ('overlayId' in context) return context.overlayId + ':' + context.density;
  return context.workspace + ':' + context.contextKey;
}

export function readFileChanges(event: FsChangeEvent): string[] {
  if (event.error) return [event.error.code];
  const changes: FsChange[] = event.changes;
  const truncated: boolean = event.truncated;
  const contextKey: string = event.contextKey;
  return changes.map(change => contextKey + ':' + change.kind + ':' + change.path + ':' + truncated);
}

export const fileWatchFailure: FsChangeEvent = {
  subscriptionId: 'watch-1',
  path: 'data://records',
  error: { code: 'WATCH_FAILED', message: 'watch failed' }
};

export const fileWatchChanges: FsChangeEvent = {
  subscriptionId: 'watch-1',
  path: 'data://records',
  contextKey: 'workspace-a',
  changes: [{ path: 'data://records/a.json', kind: 'modified' }],
  truncated: false
};

type Input = InstanceType<GlobalComponents['AicodeInput']>;
type Button = InstanceType<GlobalComponents['AicodeButton']>;
type Dialog = InstanceType<GlobalComponents['AicodeDialog']>;

export const inputProps: Input['$props'] = {
  modelValue: 'hello',
  'onUpdate:modelValue': (value: string) => value.toUpperCase()
};
export const buttonProps: Button['$props'] = { onClick: (event: MouseEvent) => event.preventDefault() };
export const dialogProps: Dialog['$props'] = { onConfirm: () => {}, onCancel: () => {} };

export function emitInput(input: Input): void {
  input.$emit('update:modelValue', 'hello');
  // @ts-expect-error 输入框的更新事件只接受字符串。
  input.$emit('update:modelValue', 123);
}
