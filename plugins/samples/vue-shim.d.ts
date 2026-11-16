/**
 * samples 共享的页面类型声明：
 * - 允许页面入口从 `.vue` 文件导入组件；
 * - 声明宿主注入的 eruda / Vue Router 全局对象。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue';

  const component: DefineComponent;
  export default component;
}

interface VueRouterRouteRecord {
  path: string;
  redirect?: string;
  component?: unknown;
}

interface VueRouterInstance {
  install(app: unknown): void;
  replace(path: string): void;
  isReady(): Promise<void>;
}

interface VueRouterGlobal {
  createRouter(options: { history: unknown; routes: VueRouterRouteRecord[] }): VueRouterInstance;
  createWebHashHistory(): unknown;
}

interface ErudaApi {
  init(): void;
  show(): void;
}

interface Window {
  eruda?: ErudaApi;
  VueRouter: VueRouterGlobal;
}
