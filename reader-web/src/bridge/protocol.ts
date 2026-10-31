export const PROTOCOL_VERSION = 1
export type ToWeb = { v: 1; type: 'ping'; id: number }
export type FromWeb =
  | { v: 1; type: 'ready'; engine: string }
  | { v: 1; type: 'pong'; id: number }
  | { v: 1; type: 'error'; kind: string; message: string }
