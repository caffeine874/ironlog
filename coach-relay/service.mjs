import { RelayError } from './errors.mjs';
import { fingerprint, newContinuation, requestIdentity, secretEqual, validateInput } from './protocol.mjs';

export class CoachService {
  constructor(runner, { now = Date.now, ttl = 30 * 60 * 1000, maxEntries = 64, modelsTtl = 60000 } = {}) {
    this.runner = runner;
    this.now = now;
    this.ttl = ttl;
    this.maxEntries = maxEntries;
    this.requests = new Map();
    this.active = false;
    this.healthPending = null;
    this.modelsTtl = modelsTtl;
    this.modelsCache = null;
    this.modelsPending = null;
  }

  health() {
    // A tap storm must not create an unbounded number of app-server processes.
    if (!this.healthPending) this.healthPending = this.runner.health().finally(() => { this.healthPending = null; });
    return this.healthPending;
  }

  models() {
    if (this.modelsCache && this.now() - this.modelsCache.created < this.modelsTtl) return Promise.resolve(this.modelsCache.value);
    if (!this.modelsPending) this.modelsPending = this.runner.models().then((value) => {
      this.modelsCache = { value, created: this.now() };
      return value;
    }).finally(() => { this.modelsPending = null; });
    return this.modelsPending;
  }

  cleanup() {
    for (const [key, entry] of this.requests) if (!entry.pending && this.now() - entry.created > this.ttl) this.requests.delete(key);
  }

  async chat(raw) {
    const input = validateInput(raw);
    this.cleanup();
    let entry = this.requests.get(input.requestId);
    const identity = requestIdentity(input);
    if (entry && entry.identity !== identity) throw new RelayError('request_conflict', '同じ質問 ID の内容が変わっています。新しい質問として送信してください。', 409);
    if (input.continuation && (!entry?.continuation || !secretEqual(entry.continuation, input.continuation))) throw new RelayError('continuation_expired', '追加履歴の取得期限が切れました。質問をもう一度送信してください。', 409);
    const phase = input.continuation ? 'history' : 'initial';
    const phaseHash = fingerprint(input);
    if (entry?.[phase]) {
      if (entry[phase].hash !== phaseHash) throw new RelayError('request_conflict', '送信内容が変わっています。新しい質問として送信してください。', 409);
      return entry[phase].promise;
    }
    if (this.active) throw new RelayError('busy', '前の回答を作成しています。少し待ってからお試しください。', 429);
    if (!entry) {
      while (this.requests.size >= this.maxEntries) {
        const removable = [...this.requests].find(([, candidate]) => !candidate.pending);
        if (!removable) throw new RelayError('busy', 'しばらく待ってからお試しください。', 429);
        this.requests.delete(removable[0]);
      }
      entry = { identity, created: this.now() };
      this.requests.set(input.requestId, entry);
    }
    this.active = true;
    entry.pending = true;
    const promise = this.runner.answer(input, entry.historyRequest, entry.selection).then((response) => {
      if (response.model && response.effort) entry.selection = { model: response.model, effort: response.effort };
      if (response.historyRequest) {
        if (input.continuation) throw new RelayError('invalid_response', '追加履歴の取得回数を超えました。質問を絞ってお試しください。');
        entry.continuation = newContinuation();
        entry.historyRequest = response.historyRequest;
        return { historyRequest: response.historyRequest, continuation: entry.continuation, ...(entry.selection ?? {}) };
      }
      return response;
    }).catch((error) => {
      // Transport failures can be explicitly retried with the same request ID.
      // Completed replies remain cached, including when the phone disconnected.
      delete entry[phase];
      throw error;
    }).finally(() => { this.active = false; entry.pending = false; });
    entry[phase] = { hash: phaseHash, promise };
    return promise;
  }
}
