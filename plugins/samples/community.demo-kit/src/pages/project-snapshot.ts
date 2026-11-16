import { createApp } from 'vue';
import App from './project-snapshot.vue';

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount('#app');
