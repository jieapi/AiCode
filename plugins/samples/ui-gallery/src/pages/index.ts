import { createApp } from 'vue';
import App from './index.vue';
import GalleryView from './views/GalleryView.vue';
import ConfigView from './views/ConfigView.vue';

if (window.eruda) window.eruda.init();

const router = window.VueRouter.createRouter({
  history: window.VueRouter.createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/gallery' },
    { path: '/gallery', component: GalleryView },
    { path: '/config', component: ConfigView },
    { path: '/:pathMatch(.*)*', redirect: '/gallery' }
  ]
});

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.use(router);
router.isReady().then(() => {
  app.mount('#app');
});
