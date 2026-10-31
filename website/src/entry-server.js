import { createSSRApp } from "vue";
import { renderToString } from "vue/server-renderer";
import App from "./App.vue";
import { vReveal } from "./directives/reveal";
import { createLocale, getLocaleFromPath, LOCALE_KEY } from "./composables/useLocale.js";

export function render(pathname = "/") {
  const app = createSSRApp(App);
  app.provide(LOCALE_KEY, createLocale(getLocaleFromPath(pathname)));
  app.directive("reveal", vReveal);
  return renderToString(app);
}
