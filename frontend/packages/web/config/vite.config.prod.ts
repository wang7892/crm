import configCompressPlugin from './plugin/compress';
import configVisualizerPlugin from './plugin/visualizer';
import baseConfig from './vite.config.base';
import legacy from '@vitejs/plugin-legacy';
import { mergeConfig } from 'vite';

export default mergeConfig(
  {
    mode: 'production',
    plugins: [
      configCompressPlugin('gzip'),
      configVisualizerPlugin(),
      // modernTargets 默认含 chrome64/safari12，esbuild 0.27+ 无法把解构降到该环境；提高 modern 基线，旧浏览器仍走 legacy SystemJS
      legacy({
        targets: ['defaults', 'not IE 11'],
        modernTargets: [
          'chrome>=92',
          'edge>=92',
          'firefox>=91',
          'safari>=15',
          'chromeAndroid>=92',
          'iOS>=15',
        ],
      }),
    ],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia', '@vueuse/core', 'vue-i18n'],
            chart: ['echarts', 'vue-echarts'],
          },
        },
      },
      chunkSizeWarningLimit: 2000,
    },
  },
  baseConfig
);
