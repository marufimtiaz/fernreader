import { Bridge } from './bridge/bridge'
import { Reader } from './reader/reader'

declare global {
  interface Window {
    ReaderBridge?: { postMessage(s: string): void }
    __reader: { receive(json: string): void }
  }
}

const bridge = new Bridge(json => window.ReaderBridge?.postMessage(json))
new Reader(bridge) // imports foliate-js view.js: the module graph is warm before any book is opened
window.__reader = { receive: json => bridge.receive(json) }
bridge.send({ v: 1, type: 'ready', engine: 'foliate-js@78914ae' })
