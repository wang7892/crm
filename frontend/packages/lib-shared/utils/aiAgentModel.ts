// Names are keyed by the model saved with the message, independently of the model selector.
const modelLabels = new Map([
  ['gpt-6.1-sol', 'GPT-6.1 Sol'],
  ['gpt-6-astra', 'GPT-6 Astra'],
  ['gpt-5.6-sol', 'GPT-5.6 Sol'],
  ['deepseek-flash', 'DeepSeek-Flash'],
]);

const providerLabels = new Map([
  ['primary', 'GPT'],
  ['deepseek', 'DeepSeek'],
]);

export default function getAiAgentModelLabel(response?: { llmProvider?: string; llmModel?: string }) {
  const model = typeof response?.llmModel === 'string' ? response.llmModel.trim() : '';
  if (model) return modelLabels.get(model.toLowerCase()) || model;
  const provider = typeof response?.llmProvider === 'string' ? response.llmProvider.trim() : '';
  if (!provider || provider === 'none') return undefined;
  return providerLabels.get(provider.toLowerCase()) || provider;
}
