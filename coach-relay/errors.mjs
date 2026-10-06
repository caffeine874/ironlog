export class RelayError extends Error {
  constructor(code, message, status = 502) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export function publicError(error) {
  return error instanceof RelayError ? error : new RelayError('codex_error', 'AI の応答を取得できませんでした。PC の接続状態を確認して、もう一度お試しください。');
}
