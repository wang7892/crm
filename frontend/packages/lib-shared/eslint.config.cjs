const { defineConfig, globalIgnores } = require('eslint/config');
const { FlatCompat } = require('@eslint/eslintrc');
const { fixupConfigRules } = require('@eslint/compat');
const js = require('@eslint/js');
const pluginVue = require('eslint-plugin-vue');
const legacyConfig = require('./.eslintrc.cjs');

const compat = new FlatCompat({
  baseDirectory: __dirname,
  recommendedConfig: js.configs.recommended,
  allConfig: js.configs.all,
});

module.exports = defineConfig([
  {
    files: ['**/*.{js,jsx,ts,tsx,vue}'],
    languageOptions: {
      parserOptions: {
        // Vue expressions are modules; Node's global-return scope hides v-for variables.
        ecmaFeatures: { globalReturn: false },
      },
    },
    rules: {
      // Function declarations are hoisted; keep checks for variables and classes.
      'no-use-before-define': ['error', { functions: false }],
    },
    extends: [
      ...pluginVue.configs['flat/recommended'],
      ...fixupConfigRules(
        compat.config({
          ...legacyConfig,
          env: { browser: true, node: true },
          extends: legacyConfig.extends.filter((name) => name !== 'plugin:vue/vue3-recommended'),
          globals: {
            ...legacyConfig.globals,
            defineProps: 'readonly',
            defineEmits: 'readonly',
            defineExpose: 'readonly',
            withDefaults: 'readonly',
          },
        })
      ),
    ],
  },
  globalIgnores(['*.json', 'src/**/*.json', '**/dist/**', '**/postcss.config.js', '**/*.md']),
]);
