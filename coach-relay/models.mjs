import { RelayError } from './errors.mjs';

const modelError = () => new RelayError('codex_unavailable', 'ChatGPT 契約で利用できる Codex モデルを取得できませんでした。', 503);
const validName = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max && !/[\u0000-\u0020\u007f]/.test(value);

export const automaticEffort = (model) => model.supportedReasoningEfforts.includes('low') ? 'low' : model.defaultReasoningEffort;

export async function fetchModelCatalog(client, config = {}) {
  const models = new Map();
  const cursors = new Set();
  let cursor;
  // Bound a broken or unexpectedly huge upstream catalog, including cursor loops.
  for (let page = 0; page < 10; page++) {
    const response = await client.call('model/list', { limit: 100, includeHidden: false, ...(cursor ? { cursor } : {}) });
    if (!Array.isArray(response?.data)) throw modelError();
    for (const entry of response.data) {
      if (!validName(entry.model, 128) || !validName(entry.defaultReasoningEffort, 32) || !Array.isArray(entry.supportedReasoningEfforts)) throw modelError();
      const efforts = entry.supportedReasoningEfforts.map((option) => option.reasoningEffort);
      if (efforts.some((effort) => !validName(effort, 32)) || efforts.length > 32) throw modelError();
      // The advertised default is also valid on providers that omit it from the
      // optional picker choices. Never invent unadvertised reasoning levels.
      models.set(entry.model, {
        id: entry.model,
        displayName: typeof entry.displayName === 'string' ? entry.displayName.slice(0, 200) : entry.model,
        defaultReasoningEffort: entry.defaultReasoningEffort,
        supportedReasoningEfforts: [...new Set([...efforts, entry.defaultReasoningEffort])],
        isDefault: Boolean(entry.isDefault)
      });
      if (models.size > 200) throw modelError();
    }
    if (!response.nextCursor) {
      const list = [...models.values()];
      const defaultModel = config.model ? models.get(config.model) : list.find((model) => model.isDefault) ?? list[0];
      if (!defaultModel) throw modelError();
      return { models: list, defaultModel: defaultModel.id, defaultReasoningEffort: automaticEffort(defaultModel) };
    }
    if (typeof response.nextCursor !== 'string' || response.nextCursor.length > 4096 || cursors.has(response.nextCursor)) throw modelError();
    cursors.add(response.nextCursor);
    cursor = response.nextCursor;
  }
  throw modelError();
}

export function selectModel(catalog, input = {}, frozenSelection) {
  const requestedModel = frozenSelection?.model ?? input.model ?? catalog.defaultModel;
  const model = catalog.models.find((entry) => entry.id === requestedModel);
  if (!model) throw new RelayError('unsupported_model', '選んだモデルは現在利用できません。モデル一覧を更新して選び直してください。', 400);
  const effort = frozenSelection?.effort ?? input.effort ?? automaticEffort(model);
  if (!model.supportedReasoningEfforts.includes(effort)) throw new RelayError('unsupported_effort', '選んだ推論レベルはこのモデルで利用できません。モデル一覧を更新して選び直してください。', 400);
  return { model: model.id, effort };
}
