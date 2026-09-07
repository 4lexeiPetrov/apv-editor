package com.apv.editor

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apv.editor.platform.AutosaveMode
import com.apv.editor.platform.FileDialogs
import com.apv.editor.platform.FileOpenEvents
import com.apv.editor.session.SessionStore
import com.apv.editor.session.WindowSnapshot
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneLayout
import com.apv.editor.state.TabState
import com.apv.editor.state.buildRestoredSession
import com.apv.editor.syntax.SyntaxLanguage
import com.apv.editor.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import java.awt.Frame
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.time.Duration

fun main(args: Array<String>) {
    FileOpenEvents.install(args)
    application {
        val sessionStore = remember { SessionStore() }
        // Cheap: session.json holds only metadata (paths, cursors, layout, window geometry) — no
        // file contents — so this stays synchronous and the saved window size/position are ready
        // in time for rememberWindowState below.
        val initialSnapshot = remember { sessionStore.load() }

        // Start with a single blank tab so the window can appear immediately. The expensive part
        // of restoring a session — reading every previously-open file (and draft) off disk — runs
        // in the LaunchedEffect below, off the UI thread, and replaces this once it completes.
        val state = remember { EditorState().also { it.newTab() } }

        LaunchedEffect(initialSnapshot, sessionStore, state) {
            val snapshot = initialSnapshot ?: return@LaunchedEffect
            val restored = withContext(Dispatchers.IO) {
                buildRestoredSession(snapshot, sessionStore::readDraft)
            }
            state.applyRestored(restored)
        }

        DisposableEffect(state) {
            FileOpenEvents.setListener { file -> state.openFile(file) }
            onDispose {
                FileOpenEvents.clearListener()
                sessionStore.shutdown()
            }
        }
        val allPanesEmpty = state.panes.all { it.tabs.isEmpty() }
        LaunchedEffect(allPanesEmpty) {
            if (allPanesEmpty) {
                sessionStore.flush()
                exitApplication()
            }
        }
        val initialWindow = initialSnapshot?.window
        val initialPosition = if (initialWindow?.x != null && initialWindow.y != null) {
            WindowPosition(initialWindow.x.dp, initialWindow.y.dp)
        } else {
            WindowPosition.PlatformDefault
        }
        val windowState: WindowState = rememberWindowState(
            width = (initialWindow?.width ?: 1100).dp,
            height = (initialWindow?.height ?: 760).dp,
            position = initialPosition,
        )

        LaunchedEffect(state, sessionStore, windowState) {
            snapshotFlow {
                buildList<Any?> {
                    add(state.layout)
                    add(state.fontSize)
                    add(state.focusedPaneId)
                    add(windowState.size)
                    add(windowState.position)
                    for (pane in state.panes) {
                        add(pane.id)
                        add(pane.selectedIndex)
                        for (tab in pane.tabs) {
                            add(tab.id)
                            add(tab.file)
                            add(tab.language)
                            add(tab.showPreview)
                            add(tab.textField)
                            add(tab.savedText)
                        }
                    }
                }
            }
                // snapshotFlow re-runs the block above (and rebuilds this list) on every write
                // to any state it reads — including pure cursor/selection moves, since each tab's
                // TextFieldValue bundles selection with text as one Compose State. That rebuild
                // itself isn't what this debounce avoids (Compose's snapshot system requires it to
                // detect a change in the first place); what it avoids is calling sessionStore
                // .schedule(...) — and its ScheduledFuture submit/cancel churn on the background
                // scheduler — on every single one of those emissions instead of once per pause.
                // The disk write itself is already separately debounced 800ms inside SessionStore.
                // onCloseRequest / the allPanesEmpty exit path flush synchronously outside this
                // flow, so final state on quit is unaffected.
                .debounce(250)
                .collect {
                    sessionStore.schedule { state.toWrite(snapshotOfWindow(windowState)) }
                }
        }

        val appIcon = remember {
            useResource("icons/icon-512.png") { stream ->
                BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
            }
        }

        // Pre-resolve strings needed outside Composable context (click handlers, helpers).
        val appName = stringResource(Res.string.app_name)
        val fmtSaved = stringResource(Res.string.status_saved)
        val fmtSyntax = stringResource(Res.string.status_syntax)
        val strMdOn = stringResource(Res.string.status_markdown_preview_enabled)
        val strMdOff = stringResource(Res.string.status_markdown_preview_disabled)
        val strOpenTitle = stringResource(Res.string.dialog_open_file)
        val strSaveTitle = stringResource(Res.string.dialog_save_as)

        Window(
            onCloseRequest = {
                // Small-file tabs' unsaved edits survive quit via the draft snapshot the line
                // below captures. Large-file tabs don't get a draft (see EditorState.tabToSnapshot)
                // — the file on disk *is* their buffer — so anything dirty needs an actual save
                // here instead, rather than trusting the debounced autosave to have already fired.
                flushDirtyLargeTabs(state)
                sessionStore.schedule { state.toWrite(snapshotOfWindow(windowState)) }
                sessionStore.flush()
                exitApplication()
            },
            state = windowState,
            title = state.currentTab?.title ?: appName,
            icon = appIcon,
        ) {
            val parent: Frame? = window

            // AutosaveMode.OnWindowChange: save every dirty tab when the app is backgrounded
            // (Alt/Cmd-Tab away, click another app) — distinct from AutosaveMode.OnFocusChange,
            // which is per-tab and driven by EditorPane/LargeEditorPane's own composable lifecycle.
            DisposableEffect(window, state) {
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent) {}
                    override fun windowLostFocus(e: WindowEvent) {
                        state.autosaveAllOnWindowFocusLost()
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }

            MenuBar {
                Menu(stringResource(Res.string.menu_file), mnemonic = 'F') {
                    Item(stringResource(Res.string.menu_file_new), shortcut = KeyShortcut(Key.N, meta = true)) {
                        state.newTab()
                    }
                    Item(stringResource(Res.string.menu_file_open), shortcut = KeyShortcut(Key.O, meta = true)) {
                        FileDialogs.openFiles(parent, strOpenTitle).forEach { state.openFile(it) }
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_file_save), shortcut = KeyShortcut(Key.S, meta = true)) {
                        state.currentTab?.let { saveTab(it, state, parent, fmtSaved, strSaveTitle) }
                    }
                    Item(stringResource(Res.string.menu_file_save_as), shortcut = KeyShortcut(Key.S, meta = true, shift = true)) {
                        state.currentTab?.let { saveTabAs(it, state, parent, fmtSaved, strSaveTitle) }
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_file_close_tab), shortcut = KeyShortcut(Key.W, meta = true)) {
                        state.closeTab(state.selectedIndex)
                    }
                }
                Menu(stringResource(Res.string.menu_edit), mnemonic = 'E') {
                    Item(stringResource(Res.string.menu_edit_undo), shortcut = KeyShortcut(Key.Z, meta = true)) {
                        state.undo()
                    }
                    Item(stringResource(Res.string.menu_edit_redo), shortcut = KeyShortcut(Key.Z, meta = true, shift = true)) {
                        state.redo()
                    }
                }
                Menu(stringResource(Res.string.menu_view), mnemonic = 'V') {
                    Item(stringResource(Res.string.menu_view_increase_font), shortcut = KeyShortcut(Key.Equals, meta = true)) {
                        state.fontSize = (state.fontSize + 2).coerceAtMost(48)
                    }
                    Item(stringResource(Res.string.menu_view_decrease_font), shortcut = KeyShortcut(Key.Minus, meta = true)) {
                        state.fontSize = (state.fontSize - 2).coerceAtLeast(8)
                    }
                    Separator()
                    val markdownTab = state.currentTab?.takeIf { it.language == SyntaxLanguage.MARKDOWN }
                    CheckboxItem(
                        text = stringResource(Res.string.menu_view_markdown_preview),
                        checked = markdownTab?.showPreview == true,
                        enabled = markdownTab != null,
                        shortcut = KeyShortcut(Key.P, meta = true, shift = true),
                        onCheckedChange = { checked ->
                            markdownTab?.showPreview = checked
                            state.statusMessage = if (checked) strMdOn else strMdOff
                        },
                    )
                    Separator()
                    Menu(stringResource(Res.string.menu_view_layout), mnemonic = 'L') {
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_single),
                            selected = state.layout == PaneLayout.Single,
                            shortcut = KeyShortcut(Key.One, meta = true, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Single) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_columns2),
                            selected = state.layout == PaneLayout.Columns2,
                            shortcut = KeyShortcut(Key.Two, meta = true, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Columns2) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_rows2),
                            selected = state.layout == PaneLayout.Rows2,
                            shortcut = KeyShortcut(Key.Two, meta = true, alt = true, shift = true),
                            onClick = { state.applyLayout(PaneLayout.Rows2) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_grid4),
                            selected = state.layout == PaneLayout.Grid2x2,
                            shortcut = KeyShortcut(Key.Five, meta = true, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Grid2x2) },
                        )
                    }
                    Separator()
                    Menu(stringResource(Res.string.menu_view_syntax), mnemonic = 'S') {
                        val currentLanguage = state.currentTab?.language
                        SyntaxLanguage.entries.forEach { lang ->
                            RadioButtonItem(
                                text = lang.displayName,
                                selected = currentLanguage == lang,
                                onClick = {
                                    val tab = state.currentTab ?: return@RadioButtonItem
                                    tab.language = lang
                                    state.statusMessage = fmtSyntax.format(lang.displayName)
                                },
                            )
                        }
                    }
                    Separator()
                    Menu(stringResource(Res.string.menu_view_autosave), mnemonic = 'A') {
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_off),
                            selected = state.autosaveMode == AutosaveMode.Off,
                            onClick = { state.autosaveMode = AutosaveMode.Off },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_after_delay),
                            selected = state.autosaveMode is AutosaveMode.AfterDelay,
                            onClick = { state.autosaveMode = AutosaveMode.AfterDelay(1500) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_on_focus_change),
                            selected = state.autosaveMode == AutosaveMode.OnFocusChange,
                            onClick = { state.autosaveMode = AutosaveMode.OnFocusChange },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_on_window_change),
                            selected = state.autosaveMode == AutosaveMode.OnWindowChange,
                            onClick = { state.autosaveMode = AutosaveMode.OnWindowChange },
                        )
                    }
                }
            }

            App(
                state = state,
                onSaveTab = { tab -> saveTab(tab, state, parent, fmtSaved, strSaveTitle) },
                onOpenInPane = { pane ->
                    FileDialogs.openFiles(parent, strOpenTitle).forEach { file -> state.openFile(pane, file) }
                },
            )
        }
    }
}

private fun flushDirtyLargeTabs(state: EditorState) {
    for (pane in state.panes) {
        for (tab in pane.tabs) {
            if (tab.isLargeFile && tab.isModified) {
                try { tab.save() } catch (_: Throwable) { /* best-effort; app is exiting either way */ }
            }
        }
    }
}

private fun saveTab(
    tab: TabState,
    state: EditorState,
    parent: Frame?,
    fmtSaved: String,
    saveDialogTitle: String,
): Boolean =
    if (tab.file == null) {
        saveTabAs(tab, state, parent, fmtSaved, saveDialogTitle)
    } else {
        tab.save()
        state.statusMessage = fmtSaved.format(tab.file?.name ?: "")
        true
    }

private fun saveTabAs(
    tab: TabState,
    state: EditorState,
    parent: Frame?,
    fmtSaved: String,
    saveDialogTitle: String,
): Boolean {
    val target = FileDialogs.saveFile(parent, tab.file?.name, saveDialogTitle) ?: return false
    tab.saveAs(target)
    state.statusMessage = fmtSaved.format(target.name)
    return true
}

private fun snapshotOfWindow(windowState: WindowState): WindowSnapshot {
    val size = windowState.size
    val pos = windowState.position
    val (x, y) = if (pos is WindowPosition.Absolute) {
        pos.x.value.toInt() to pos.y.value.toInt()
    } else {
        null to null
    }
    return WindowSnapshot(
        width = size.width.value.toInt().coerceAtLeast(200),
        height = size.height.value.toInt().coerceAtLeast(200),
        x = x,
        y = y,
    )
}
