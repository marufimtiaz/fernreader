import '../vendor/foliate-js/view.js' // registers <foliate-view>; warms the module graph before any book is opened
import { Bridge } from './bridge/bridge'

declare global {
  interface Window {
    ReaderBridge?: { postMessage(s: string): void }
    __reader: { receive(json: string): void }
  }
}

const post = (json: string) => window.ReaderBridge?.postMessage(json)
const bridge = new Bridge(post)
window.__reader = { receive: json => bridge.receive(json) }
bridge.send({ v: 1, type: 'ready', engine: 'foliate-js@78914ae' })
