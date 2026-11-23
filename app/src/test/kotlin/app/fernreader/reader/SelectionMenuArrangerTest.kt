package app.fernreader.reader

import android.view.Menu
import android.view.MenuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * F-060: the selection action mode keeps the system's text actions (PROCESS_TEXT activities, the smart text-assist
 * item), hides the WebView's own Copy/Share/Select all/Web search, and puts ours first. Menu and MenuItem are faked
 * with proxies (no Robolectric): only what the arranger uses is implemented.
 */
class SelectionMenuArrangerTest {
    private class Item(val group: Int, val id: Int, val order: Int, val title: CharSequence, val processText: Boolean = false) {
        var visible = true
        lateinit var proxy: MenuItem
    }

    private class FakeMenu {
        val items = mutableListOf<Item>()
        fun item(i: Item): MenuItem {
            i.proxy = Proxy.newProxyInstance(MenuItem::class.java.classLoader, arrayOf(MenuItem::class.java)) { self, m, args ->
                when (m.name) {
                    "getGroupId" -> i.group
                    "getItemId" -> i.id
                    "getOrder" -> i.order
                    "getTitle" -> i.title
                    "isVisible" -> i.visible
                    "setVisible" -> { i.visible = args[0] as Boolean; self }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args[0]
                    "toString" -> "Item(${i.title})"
                    else -> error("not faked: ${m.name}")
                }
            } as MenuItem
            return i.proxy
        }
        fun sorted() = items.sortedBy { it.order } // MenuBuilder orders items by their order (stable)
        val menu: Menu = Proxy.newProxyInstance(Menu::class.java.classLoader, arrayOf(Menu::class.java)) { self, m, args ->
            when (m.name) {
                "size" -> items.size
                "getItem" -> sorted()[args[0] as Int].proxy
                "findItem" -> items.firstOrNull { it.id == args[0] as Int }?.proxy
                "add" -> {
                    check(args.size == 4) { "add(group, id, order, title) only" }
                    val i = Item(args[0] as Int, args[1] as Int, args[2] as Int, args[3] as CharSequence)
                    items += i; item(i)
                }
                "clear" -> { items.clear(); Unit }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args[0]
                else -> error("not faked: ${m.name}")
            }
        } as Menu
        fun system(group: Int, id: Int, order: Int, title: String, processText: Boolean = false) =
            Item(group, id, order, title, processText).also { items += it; item(it) }
        fun visibleTitles() = sorted().filter { it.visible }.map { it.title.toString() }
    }

    /**
     * Like the WebView's menu (orders as logged on the emulator's WebView: Copy 20, Share 50, Select all 60, PROCESS_TEXT
     * 75+): its own items, the smart text-assist item, two PROCESS_TEXT activities.
     */
    private fun webViewMenu() = FakeMenu().apply {
        system(0, android.R.id.textAssist, 1, "Call")
        system(0x2010001, android.R.id.copy, 20, "Copy")
        system(0x2010001, android.R.id.shareText, 50, "Share")
        system(0x2010001, android.R.id.selectAll, 60, "Select all")
        system(0x2010001, 0x7f0b0042, 70, "Web search")
        system(0x7f0b0050, 0, 75, "Translate", processText = true)
        system(0x7f0b0050, 0, 76, "Read aloud", processText = true)
    }

    private val labels = mapOf(
        SelectionAction.HIGHLIGHT to "Highlight", SelectionAction.UNDERLINE to "Underline", SelectionAction.SEARCH to "Search in book",
        SelectionAction.COPY to "Copy", SelectionAction.SHARE to "Share",
    )
    private fun arranger() = SelectionMenuArranger(labels::getValue) { item ->
        item.itemId == android.R.id.textAssist || (item.groupId == 0x7f0b0050)
    }

    @Test fun ours_first_then_the_system_text_actions_and_no_duplicates() {
        val m = webViewMenu()
        arranger().arrange(m.menu)
        assertEquals(listOf("Highlight", "Underline", "Search in book", "Copy", "Share", "Call", "Translate", "Read aloud"), m.visibleTitles())
    }

    @Test fun clicks_on_our_items_map_to_actions_and_system_items_are_not_ours() {
        val m = webViewMenu()
        val a = arranger()
        a.arrange(m.menu)
        val byTitle = m.items.associateBy { it.title.toString() }
        assertEquals(SelectionAction.HIGHLIGHT, a.actionFor(m.items.first { it.title == "Highlight" }.proxy))
        assertEquals(SelectionAction.COPY, a.actionFor(m.items.last { it.title == "Copy" }.proxy))
        assertNull(a.actionFor(byTitle.getValue("Translate").proxy))
        assertNull(a.actionFor(m.items.first { it.title == "Copy" && it.id == android.R.id.copy }.proxy)) // the system's copy
    }

    @Test fun preparing_again_adds_ours_once_and_after_the_system_rebuilt_its_menu_adds_them_again() {
        val m = webViewMenu()
        val a = arranger()
        a.arrange(m.menu); a.arrange(m.menu)
        assertEquals(5, m.items.count { it.group == SelectionMenuArranger.GROUP })
        m.menu.clear(); m.system(0x2010001, android.R.id.copy, 20, "Copy"); m.system(0x7f0b0050, 0, 75, "Translate")
        a.arrange(m.menu)
        assertEquals(listOf("Highlight", "Underline", "Search in book", "Copy", "Share", "Translate"), m.visibleTitles())
        assertTrue(a.actionFor(m.items.first { it.title == "Share" }.proxy) == SelectionAction.SHARE)
        assertFalse(m.items.first { it.id == android.R.id.copy }.visible)
    }
}
