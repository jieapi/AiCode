import 'vue';

declare module 'vue' {
  interface ComponentCustomProperties {
    $route: { path: string };
    $router: { replace(path: string): void };
  }
}
