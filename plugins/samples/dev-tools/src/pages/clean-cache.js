import { createApp } from 'vue';
import App from './clean-cache.vue';

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount('#app');
