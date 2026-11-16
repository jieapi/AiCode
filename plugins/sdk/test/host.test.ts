import { hostCall } from '@jieapi/plugin-sdk';
import type {
  AppInfo,
  FsReadResult,
  HostMethod,
  HostParamsOf,
  HostResultOf,
  TipResult
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

  void read;
  void info;
  void foreground;
  void keys;
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
}

void implicitInference;
void legacyExplicitTypeParam;
void rejectedCalls;
