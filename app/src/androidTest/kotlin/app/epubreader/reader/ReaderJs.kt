package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import app.epubreader.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Evaluates [js] in the reader WebView (main thread) and returns its JSON result text ("null" if none). */
suspend fun ActivityScenario<MainActivity>.js(js: String): String {
    val d = CompletableDeferred<String>()
    onActivity { a -> a.reader.webView.evaluateJavascript(js) { d.complete(it ?: "null") } }
    return withTimeout(5_000) { d.await() }
}

/** `window.__readerTest` result as a JSON object. */
suspend fun ActivityScenario<MainActivity>.hook(call: String): JSONObject = JSONObject(js("JSON.stringify(window.__readerTest.$call)").let {
    // evaluateJavascript returns the string value JSON-quoted
    org.json.JSONTokener(it).nextValue() as String
})
