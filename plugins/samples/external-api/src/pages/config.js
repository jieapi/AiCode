import { createApp } from 'vue';
import App from './config.vue';

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount('#app');
