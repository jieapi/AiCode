import { createApp } from 'vue';
import App from './build-apk.vue';

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount('#app');
