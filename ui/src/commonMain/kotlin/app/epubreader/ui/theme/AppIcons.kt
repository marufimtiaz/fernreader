package app.epubreader.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * C-086: every icon the app shows, by what it stands for. All from the Material icons (core and extended sets,
 * Apache-2.0), all in the Filled style (direction-dependent ones auto-mirrored); R8 strips the rest of the set from the
 * release APK. Screens use these names, never `Icons.*` directly, so the style stays one.
 */
object AppIcons {
    // Bars
    val Menu: ImageVector get() = Icons.Filled.Menu
    val Back: ImageVector get() = Icons.AutoMirrored.Filled.ArrowBack
    val Close: ImageVector get() = Icons.Filled.Close
    val Search: ImageVector get() = Icons.Filled.Search
    val Sort: ImageVector get() = Icons.AutoMirrored.Filled.Sort
    val ShowAsList: ImageVector get() = Icons.AutoMirrored.Filled.ViewList
    val ShowAsGrid: ImageVector get() = Icons.Filled.GridView
    val More: ImageVector get() = Icons.Filled.MoreVert
    val SelectAll: ImageVector get() = Icons.Filled.SelectAll
    val AddToCollection: ImageVector get() = Icons.AutoMirrored.Filled.PlaylistAdd
    val Delete: ImageVector get() = Icons.Filled.Delete
    val Bookmark: ImageVector get() = Icons.Filled.BookmarkBorder
    val Bookmarked: ImageVector get() = Icons.Filled.Bookmark
    val Contents: ImageVector get() = Icons.AutoMirrored.Filled.Toc
    val Settings: ImageVector get() = Icons.Filled.Settings

    // Drawer, folders, collections
    val AllBooks: ImageVector get() = Icons.AutoMirrored.Filled.LibraryBooks
    val Folder: ImageVector get() = Icons.Filled.Folder
    val AllFolders: ImageVector get() = Icons.Filled.FolderOpen
    val Collection: ImageVector get() = Icons.Filled.CollectionsBookmark
    val Add: ImageVector get() = Icons.Filled.Add
    val Info: ImageVector get() = Icons.Filled.Info
    val Expand: ImageVector get() = Icons.Filled.ChevronRight
    val Collapse: ImageVector get() = Icons.Filled.ExpandMore
    /** C-083: the bottom bar's page label opens "Go to page" (a picker, so the dropdown mark). */
    val GoToPage: ImageVector get() = Icons.Filled.ExpandMore
    val Check: ImageVector get() = Icons.Filled.Check

    // Menus
    val AddBooks: ImageVector get() = Icons.Filled.LibraryAdd
    val Rename: ImageVector get() = Icons.Filled.DriveFileRenameOutline
    val Rescan: ImageVector get() = Icons.Filled.Refresh
    val OpenBook: ImageVector get() = Icons.Filled.FileOpen
    val Play: ImageVector get() = Icons.Filled.PlayArrow
    val RemoveFromCollection: ImageVector get() = Icons.Filled.PlaylistRemove
    val SortRecent: ImageVector get() = Icons.Filled.History
    val SortAdded: ImageVector get() = Icons.Filled.DateRange
    val SortTitle: ImageVector get() = Icons.Filled.SortByAlpha
    val SortAuthor: ImageVector get() = Icons.Filled.Person

    // Panels
    val ClearHistory: ImageVector get() = Icons.Filled.DeleteSweep
    val ShowMore: ImageVector get() = Icons.Filled.ExpandMore
    val ShowLess: ImageVector get() = Icons.Filled.ExpandLess

    // Settings
    val Theme: ImageVector get() = Icons.Filled.Palette
    val Discovery: ImageVector get() = Icons.AutoMirrored.Filled.ManageSearch
    val FolderView: ImageVector get() = Icons.Filled.AccountTree
    val Hidden: ImageVector get() = Icons.Filled.VisibilityOff
    val PageNumbers: ImageVector get() = Icons.Filled.FormatListNumbered
    val PageAnimation: ImageVector get() = Icons.Filled.Animation
    val SwipeDirection: ImageVector get() = Icons.Filled.Swipe
    val TapZones: ImageVector get() = Icons.Filled.TouchApp
    val VolumeKeys: ImageVector get() = Icons.AutoMirrored.Filled.VolumeUp
    val Fullscreen: ImageVector get() = Icons.Filled.Fullscreen
    val KeepScreenOn: ImageVector get() = Icons.Filled.LightMode
    val Orientation: ImageVector get() = Icons.Filled.ScreenRotation
    val Links: ImageVector get() = Icons.Filled.Link
    val BackUp: ImageVector get() = Icons.Filled.Backup
    val Restore: ImageVector get() = Icons.Filled.Restore
}
