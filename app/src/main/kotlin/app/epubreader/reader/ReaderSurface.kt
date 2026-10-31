package app.epubreader.reader

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The reader WebView, full size, attached once it exists (after the first frame, P-001). */
@Composable
fun ReaderSurface(engine: ReaderEngine) {
    val created by engine.created.collectAsState()
    if (!created) return
    AndroidView(
        factory = {
            engine.webView.also {
                (it.parent as? ViewGroup)?.removeView(it)
                // AndroidView's default WRAP_CONTENT makes WebView lay the page out with a zero-height viewport
                // (it sizes to content): html/body and 100vh would be 0 and foliate would paginate into nothing.
                it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
