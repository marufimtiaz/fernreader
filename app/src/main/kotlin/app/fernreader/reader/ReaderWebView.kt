package app.fernreader.reader

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/** F-060: our selection menu items, in menu order. */
enum class SelectionAction { HIGHLIGHT, UNDERLINE, SEARCH, COPY, SHARE }

/** Runs one of our selection menu items (main thread). The controller acts on the selection it last heard of. */
fun interface SelectionActionHandler {
    fun onSelectionAction(action: SelectionAction)
}

/**
 * The reader WebView. Its selection menu (the floating action mode the WebView starts on a text selection) is wrapped:
 * our items first (Highlight, Underline, Search in book, Copy, Share), then the system's text actions (PROCESS_TEXT
 * activities such as Translate, Read aloud or dictionaries, and the smart text-assist item), without the WebView's own
 * Copy, Share, Select all and Web search.
 */
class ReaderWebView(context: Context) : WebView(context) {
    var selectionActions: SelectionActionHandler? = null
    /** Titles of our items (Copy.kt). */
    var selectionLabels: (SelectionAction) -> CharSequence = { it.name }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val handler = selectionActions
        val wrapped = if (callback != null && handler != null && type == ActionMode.TYPE_FLOATING)
            SelectionActionModeCallback(callback, handler, SelectionMenuArranger(selectionLabels, ::isSystemTextAction))
        else callback
        return super.startActionMode(wrapped, type)
    }
}

/** A system text action we keep: an activity answering PROCESS_TEXT, or the smart text-assist item (call, map, ...). */
internal fun isSystemTextAction(item: MenuItem): Boolean =
    item.itemId == android.R.id.textAssist || item.intent?.action == Intent.ACTION_PROCESS_TEXT

/**
 * Arranges the selection menu after the WebView has built (or rebuilt) it: hides every item that is not ours or a
 * system text action, and adds ours once (again if the WebView cleared the menu), first.
 */
internal class SelectionMenuArranger(
    private val label: (SelectionAction) -> CharSequence,
    private val keep: (MenuItem) -> Boolean,
) {
    private val ours = HashMap<MenuItem, SelectionAction>()

    fun arrange(menu: Menu) {
        if (ours.keys.none { k -> menu.findItem(k.itemId) === k }) {
            ours.clear()
            // Order 0 for all five: the menu keeps equal orders in insertion order, and the WebView's items have orders
            // >= 1 (Copy 20, Share 50, Select all 60, PROCESS_TEXT 75+ on WebView 14x), so ours come first, in this order.
            SelectionAction.entries.forEachIndexed { i, a -> ours[menu.add(GROUP, ID_BASE + i, 0, label(a))] = a }
        }
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item !in ours && item.isVisible && !keep(item)) item.isVisible = false
        }
    }

    fun actionFor(item: MenuItem): SelectionAction? = ours[item]

    companion object {
        /** Our menu group and item ids: small numbers, far from the WebView's resource ids and android.R ids. */
        const val GROUP = 0x5e1
        const val ID_BASE = 0x5e10
    }
}

/** Wraps the WebView's floating action mode callback (it keeps positioning the toolbar, and handles its own items). */
private class SelectionActionModeCallback(
    private val system: ActionMode.Callback,
    private val handler: SelectionActionHandler,
    private val arranger: SelectionMenuArranger,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val ok = system.onCreateActionMode(mode, menu)
        dump("create", menu)
        arranger.arrange(menu)
        return ok
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        system.onPrepareActionMode(mode, menu)
        dump("prepare", menu)
        arranger.arrange(menu)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val a = arranger.actionFor(item) ?: return system.onActionItemClicked(mode, item)
        handler.onSelectionAction(a)
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = system.onDestroyActionMode(mode)

    /** The WebView's menu as built (diagnostics: `setprop log.tag.EPRSEL DEBUG`). */
    private fun dump(phase: String, menu: Menu) {
        if (!Log.isLoggable("EPRSEL", Log.DEBUG)) return
        for (i in 0 until menu.size()) menu.getItem(i).let {
            Log.d("EPRSEL", "$phase: ${it.title} group=${it.groupId} id=${it.itemId} order=${it.order} visible=${it.isVisible} intent=${it.intent?.action}")
        }
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (system is ActionMode.Callback2) system.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
}
