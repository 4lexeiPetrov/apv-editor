# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

APV Editor — a Compose Multiplatform Desktop text editor. Single Gradle module (`:composeApp`) with a single KMP target: `jvm("desktop")`. Main class: `com.apv.editor.MainKt`.

Stack: Kotlin 2.0.21, Compose Multiplatform 1.7.3 (`material3`, `materialIconsExtended`, `components.resources`), JVM toolchain 21, Gradle 9.5.1 (wrapper). Plugins: `kotlin("multiplatform")`, `org.jetbrains.compose`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`. Desktop dependencies beyond Compose: `kotlinx-coroutines-swing:1.9.0`, `kotlinx-serialization-json:1.7.3`, `com.mikepenz:multiplatform-markdown-renderer{,-m3}:0.27.0`.

## Top-level layout

Single-module structure (`composeApp/` + root Gradle scripts). Matches the modern [Kotlin Multiplatform wizard](https://kmp.jetbrains.com/) output with **Share UI** enabled — **not** the older archived [`compose-multiplatform-template`](https://github.com/JetBrains/compose-multiplatform-template) multi-module layout (`shared/` + `androidApp/` + `desktopApp/` + `iosApp/`). Only the desktop target is wired up; adding Android/iOS/Web would extend `composeApp/build.gradle.kts` and add `androidMain` / `iosMain` / `wasmJsMain` source sets — no module split needed.

- `README.md` — quick-start and project overview.
- `LICENSE.txt` — Apache 2.0.
- `CHANGELOG.md` — notable changes per version ([Keep a Changelog](http://keepachangelog.com/) format: Added / Changed / Fixed, each entry tagged MAJOR, MINOR or PATCH). Add an entry under `[Unreleased]` for any user-visible change.
- `.run/` — IntelliJ run configurations shared via VCS (`Run Desktop`, `Package DMG`). Use `.run/` (not `.idea/runConfigurations/`) because `.gitignore` excludes `.idea/`.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew{,.bat}`, `gradle/wrapper/` — standard Gradle.
- `composeApp/` — the only Gradle subproject. `launcher/` holds the macOS `AppIcon.icns` and the script that builds it.

## Commands

Use the Gradle wrapper (`./gradlew`). Always invoke tasks against `:composeApp` — the root module has no application/build outputs of its own.

- Run the app: `./gradlew :composeApp:run`
- Build (also runs tests): `./gradlew :composeApp:build`
- Tests only: `./gradlew :composeApp:desktopTest`
- Package: `./gradlew :composeApp:packageDmg` (macOS) / `:composeApp:packageMsi` (Windows). Bundle ID `com.apv.editor`; the macOS `Info.plist` declares `CFBundleDocumentTypes` so Finder routes file opens to the running app (see `FileOpenEvents`); Windows/Linux use the `fileAssociation` list in `composeApp/build.gradle.kts`, which mirrors `SyntaxDetector.extensionMap`.
- Clean: `./gradlew clean`

Tests live in `composeApp/src/commonTest` (syntax pipeline, `TextSearch`) and `composeApp/src/desktopTest` (large-file support, `TextCommands`, tab naming, start-up state in `StartupTest`, `SessionStoreTest`).

## Architecture

Two source sets, with a strict split that should be preserved when extending the app:

- `composeApp/src/commonMain/kotlin/com/apv/editor/` — pure Kotlin, no Compose / no JVM I/O. Keep it free of `androidx.compose.*` and `java.*`.
  - `syntax/` — `SyntaxLanguage` (enum with `displayName`), `SyntaxDetector` (filename → language), `SyntaxHighlighter` (text → `List<Token>`, with an incremental cache).
  - `text/TextSearch` — plain/regex search over a buffer, used by the find bar.
- `composeApp/src/desktopMain/kotlin/com/apv/editor/` — everything Compose Desktop, AWT, and `java.io`:
  - `Main.kt` — entry point, `Window`, `MenuBar`, unified-title-bar setup, and `buildCommands` (the command palette's entries).
  - `state/` — `EditorState` (panes, layout, sidebar, notices; the single source of truth held via `remember` in `Main`), `PaneState`, `TabState`, `PreviewMode`, `LargeDocController`, `LargeFileGuard`, `TextCommands` (format commands + `TabState.applyTextCommand`).
  - `ui/` — `App.kt` (root layout: sidebar | panes, status bar, overlays), `Pane.kt` (pane layouts, per-pane column), `TabBar.kt`, `FindBar.kt`, `EditorPane.kt` + `LineGutter.kt` + `Minimap.kt` (normal files), `LargeEditorPane.kt` (large files), `StartScreen.kt` (shown while no tab is open), `StatusBar.kt`, `Sidebar.kt`, `CommandPalette.kt`, `Dialogs.kt`, `MarkdownPreview.kt`, `Widgets.kt` (tooltip, compact icon button, file badge), `WheelScroll.kt` (the editors' wheel/trackpad handler), `Platform.kt` (OS switches, shortcut helper, title-bar composition locals), `EditorTheme.kt`, `HighlightTransformation.kt`.
  - `platform/` — `AutoSaver`, `AutosaveMode`, `FileDialogs` (AWT; `openFolder` for the sidebar), `FileOpenEvents`.
  - `session/` — `SessionSnapshot` DTOs and `SessionStore`.
  - `largefile/` — `LargeDocument`, the line-indexed backing store for large-file tabs.

State model: one `EditorState` per window owns the `PaneState`s for the current `PaneLayout` (single, two columns, two rows, 2x2). Each pane owns a `mutableStateListOf<TabState>` and has its own tab bar. A `TabState` carries a `TextFieldValue`, the backing `File?`, and a `savedText` snapshot for the dirty marker. `EditorState.openFile` reuses the current tab only when it is empty/unsaved/untitled.

Start screen: the app always starts with no tabs and no folder, and closing the last tab returns there instead of quitting. While `EditorState.hasNoTabs` is true, `PaneLayoutRenderer` shows one pane whose body is `StartScreen`: actions, unsaved buffers (`unsavedDrafts`, first 5 then "Show all"), recent folders and recent files. `EditorState.applyStartup` builds those lists from the previous `session.json` without reading any file. Unsaved rows show only the file name or "Untitled N", for the same reason as tab titles.

Tab titles: `TabState.title` is the file name, or "Untitled N" (`untitledIndex`, persisted) for a buffer with no file. It is never derived from buffer content — that used to leak whatever was typed (including secrets) into the tab strip and window title. The content preview exists only as the tab's hover tooltip (`contentPreview`).

Normal vs large files: `LargeFileGuard.classify` routes a file to the normal path (`EditorPane`, one `BasicTextField` over the whole text) or the large path (`LargeEditorPane`, a `LazyColumn` of line rows over a `LargeDocument`). Features that work on the in-memory text — find, format commands, Markdown preview, minimap — are normal-path only; check `tab.isLargeFile` before using `tab.textField`.

Highlighting bridge: `commonMain` produces `List<Token>` from raw text. `HighlightTransformation` is a Compose `VisualTransformation` that applies `SpanStyle`s from `EditorTheme.styleFor(TokenKind)`. It uses `OffsetMapping.Identity`, so token ranges must always stay within `text.length` — the transformation already clamps, keep that invariant if you touch it. To add a language: add an enum value in `SyntaxLanguage`, map extensions in `SyntaxDetector.extensionMap`, add a `LangSpec` (or dedicated `highlight…` function) in `SyntaxHighlighter`, and a colour in `Widgets.kt`'s `badgeColor`.

Editor decorations: the current-line highlight and find-match rectangles are drawn in a `drawBehind` in `EditorPane`, from the existing `TextLayoutResult` and the visible line range only. Nothing on the typing path may scan the whole document per keystroke; follow `LineGutter`'s visible-range pattern.

Wheel scrolling: both editor panes take wheel/trackpad events through `Modifier.axisLockedWheelScroll` (`WheelScroll.kt`), which moves one axis per gesture and consumes the event before any nested scrollable sees it. Do not add a horizontal `scrollable`/`horizontalScroll` that handles wheel events inside a vertical one: the inner one swallows vertical events once it can move both ways.

Window chrome: on macOS `Main.kt` makes the title bar transparent and full-content (`apple.awt.*` client properties) so the tab strip sits beside the traffic lights. `LocalUnifiedWindowScope` (non-null only then) lets tab bars and the sidebar header wrap their empty areas in `WindowDraggableArea`; `LocalTitleBarInset` is the start padding the top-left pane's tab bar leaves for the lights (0 in fullscreen, when the sidebar is showing, and on other platforms). Windows keeps its native title bar.

Shortcuts: declare menu shortcuts with `Platform.shortcut(Key.X, shift, alt)` (Cmd on macOS, Ctrl elsewhere), never `KeyShortcut(..., meta = true)` directly.

Commands: `buildCommands` in `Main.kt` builds the `EditorCommand` list shown by the command palette (Cmd/Ctrl+Shift+P). A new action usually needs both a `MenuBar` item and a palette entry there.

Autosave: `AutoSaver` debounces with a single-thread daemon `ScheduledExecutorService`. Modes are in `AutosaveMode` (off, after delay, on tab focus change, on window focus change). The scheduled action runs off the UI thread — only touch Compose state through snapshot-safe writes if you extend it.

External changes: each tab records `loadedMtime` when it reads or writes its file. `EditorState.checkExternalChanges` runs when the window regains focus: clean tabs reload silently, a dirty tab raises `diskChangeNotice` (the Reload notice in `App.kt`).

Session persistence: `SessionStore` writes a JSON snapshot of window/pane/tab layout, sidebar state, plus dirty buffer texts to the OS-specific app data dir (`~/Library/Application Support/APV Editor` on macOS, `%APPDATA%/APV Editor` on Windows, `$XDG_DATA_HOME/apv-editor` elsewhere). Writes are debounced and atomic. `Json` is configured with `ignoreUnknownKeys = true` and `encodeDefaults = false`, so new snapshot fields must be nullable or have defaults to stay compatible with old `session.json` files. Drafts for dirty tabs live as side files under `drafts/`, named by `TabState.draftPath` (random per tab, so a later launch never overwrites a kept draft); orphans are pruned each write, except the paths in `SessionWrite.keepDrafts` (unsaved buffers the start screen lists but no tab has open). The snapshot is not restored into tabs on launch: dirty tabs of the last session become `unsaved` entries, and the snapshot also carries `recentFolders` / `recentFiles`. Large-file tabs get no draft (the file on disk is their buffer).

Theme: `EditorTheme` is a hand-rolled dark palette (object, not a `CompositionLocal`) based on the Material Dark Soda syntax theme, with a gold accent. It also defines the Material 3 `colorScheme` and `shapes` passed to `MaterialTheme`, so dialogs, menus and buttons inherit the palette. Token colours live there too — change `styleFor` to retheme highlighting, not the highlighter itself.
