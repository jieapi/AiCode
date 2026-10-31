import { createApp, createSSRApp } from "vue";
import App from "./App.vue";
import { vReveal } from "./directives/reveal";
import "./styles.css";

const container = document.querySelector("#app");
const app = container.hasChildNodes() ? createSSRApp(App) : createApp(App);
app.directive("reveal", vReveal);
app.mount(container);
