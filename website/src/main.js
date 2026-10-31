import { createApp, createSSRApp } from "vue";
import App from "./App.vue";
import { vReveal } from "./directives/reveal";
import { createLocale, getLocaleFromPath, LOCALE_KEY } from "./composables/useLocale.js";
import "./styles.css";

const container = document.querySelector("#app");
const app = container.hasChildNodes() ? createSSRApp(App) : createApp(App);
const language = createLocale(getLocaleFromPath(window.location.pathname));
app.provide(LOCALE_KEY, language);
app.directive("reveal", vReveal);
app.mount(container);
