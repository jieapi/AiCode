import { createSSRApp } from "vue";
import { renderToString } from "vue/server-renderer";
import App from "./App.vue";
import { vReveal } from "./directives/reveal";

export function render() {
  const app = createSSRApp(App);
  app.directive("reveal", vReveal);
  return renderToString(app);
}
