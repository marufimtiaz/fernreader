// Loaded into the reader page in dev only (dev/plugin.ts): stands in for the WebView's addJavascriptInterface object.
window.ReaderBridge = { postMessage: json => window.parent.__devHost?.(json) }
