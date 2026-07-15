import { createApp } from 'vue';
// TODO 国际化接口对接
import localforage from 'localforage';

import '@/assets/style/index.less';

import { setupI18n } from '@lib/shared/locale';
import useLocale from '@lib/shared/locale/useLocale';

import CrmIcon from '@/components/pure/crm-icon-font/index.vue';
import App from './App.vue';

// eslint-disable-next-line import/no-unresolved
import 'virtual:svg-icons-register';
import directive from './directive/index';
import useDiscreteApi from './hooks/useDiscreteApi';
import router from './router';
import store from './store';

async function cleanupStaleDevServiceWorkers() {
  if (!import.meta.env.DEV || !('serviceWorker' in navigator)) {
    return;
  }

  try {
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.map((registration) => registration.unregister()));

    if ('caches' in window) {
      const cacheNames = await window.caches.keys();
      await Promise.all(cacheNames.map((cacheName) => window.caches.delete(cacheName)));
    }
  } catch (error) {
    // eslint-disable-next-line no-console
    console.warn('[Cordys CRM] Failed to clean stale dev service workers.', error);
  }
}

async function setupApp() {
  await cleanupStaleDevServiceWorkers();

  const app = createApp(App);
  const { message } = useDiscreteApi();

  app.use(store);
  // 注册国际化，需要异步阻塞，确保语言包加载完毕
  await setupI18n(app);

  app.component('CrmIcon', CrmIcon);

  // 获取默认语言
  const localLocale = localStorage.getItem('CRM-locale');
  if (!localLocale) {
    // TODO 国际化接口对接
    // const defaultLocale = await getDefaultLocale();
    const { changeLocale } = useLocale(message.loading);
    changeLocale('zh-CN');
  }

  app.use(router);
  await router.isReady();

  // 初始化本地存储
  localforage.config({
    driver: localforage.INDEXEDDB, // 选择后端存储，这里使用 IndexedDB
    name: 'CordysCrm', // 数据库名称
    version: 1.0, // 数据库版本
    storeName: 'crmTable', // 存储空间名称
  });

  app.use(directive);
  app.mount('#app');
}

setupApp();
