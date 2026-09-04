# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

APV Editor — a Compose Multiplatform Desktop text editor. Single Gradle module (`:composeApp`) with a single KMP target: `jvm("desktop")`. Main class: `com.apv.editor.MainKt`.

Stack: Kotlin 2.0.21, Compose Multiplatform 1.7.3 (`material3`, `materialIconsExtended`, `components.resources`), JVM toolchain 21, Gradle 9.5.1 (wrapper). Plugins: `kotlin("multiplatform")`, `org.jetbrains.compose`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`. Desktop dependencies beyond Compose: `kotlinx-coroutines-swing:1.9.0`, `kotlinx-serialization-json:1.7.3`, `com.mikepenz:multiplatform-markdown-renderer{,-m3}:0.27.0`.

## Top-level layout

Single-module structure (`composeApp/` + root Gradle scripts). Matches the modern [Kotlin Multiplatform wizard](https://kmp.jetbrains.com/) output with **Share UI** enabled — **not** the older archived [`compose-multiplatform-template`](https://github.com/JetBrains/compose-multiplatform-template) multi-module layout (`shared/` + `androidApp/` + `desktopApp/` + `iosApp/`). Only the desktop target is wired up; adding Android/iOS/Web would extend `composeApp/build.gradle.kts` and add `androidMain` / `iosMain` / `wasmJsMain` source sets — no module split needed.

- `README.md` — quick-start and project overview.
- `LICENSE.txt` — Apache 2.0.
- `.run/` — IntelliJ run configurations shared via VCS (`Run Desktop`, `Package DMG`). Use `.run/` (not `.idea/runConfigurations/`) because `.gitignore` excludes `.idea/`.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew{,.bat}`, `gradle/wrapper/` — standard Gradle.
- `composeApp/` — the only Gradle subproject. `launcher/` holds the macOS `AppIcon.icns` and the script that builds it.

## Commands

Use the Gradle wrapper (`./gradlew`). Always invoke tasks against `:composeApp` — the root module has no application/build outputs of its own.

- Run the app: `./gradlew :composeApp:run`
- Build: `./gradlew :composeApp:build`
- Package a macOS distribution: `./gradlew :composeApp:packageDmg` (only DMG is configured in `composeApp/build.gradle.kts`; bundle ID `com.apv.editor`; the bundle's `Info.plist` declares `CFBundleDocumentTypes` for `public.text` / `public.plain-text` / `public.source-code` / `public.script` so Finder routes file opens to the running app — see `FileOpenEvents`).
- Clean: `./gradlew clean`

There is no test source set yet — adding `commonTest` / `desktopTest` requires updating `composeApp/build.gradle.kts` before any test task will exist.

## Architecture

Two source sets, with a strict split that should be preserved when extending the app:

- `composeApp/src/commonMain/kotlin/com/apv/editor/` — pure-Kotlin syntax pipeline, no Compose / no JVM I/O. `SyntaxLanguage` (enum), `SyntaxDetector` (filename → language), `SyntaxHighlighter` (text + `LangSpec` → `List<Token>`), `TokenKind` / `Token`. This is the only code that could move to other KMP targets later; keep it free of `androidx.compose.*` and `java.*`.
- `composeApp/src/desktopMain/kotlin/com/apv/editor/` — everything Compose Desktop, AWT, and `java.io`:
  - `Main.kt` (entry point + `MenuBar` + shortcuts), `App.kt` (UI tree: `TabBar` / `EditorPane` + `LineGutter` / `StatusBar` / drag-drop overlay).
  - `EditorState.kt` — `EditorState` + `TabState`, the single source of truth held via `remember` in `Main`.
  - `AutoSaver.kt`, `FileDialogs.kt` (AWT `FileDialog`), `HighlightTransformation.kt`, `EditorTheme.kt`.
  - `MarkdownPreview.kt` — Markdown preview pane (`com.mikepenz:multiplatform-markdown-renderer-m3`) rendered inside an `EditorTheme`-themed `MaterialTheme`. Toggled per tab via `TabState.showPreview` (also persisted in `TabSnapshot`).
  - `FileOpenEvents.kt` — singleton bridging macOS `Desktop.setOpenFileHandler` (and CLI args) into the editor. Buffers files until `setListener` registers, then drains. Logs to `~/Library/Logs/APV Editor.log`. Wired up because `composeApp/build.gradle.kts` declares `CFBundleDocumentTypes`.
  - `SessionSnapshot.kt` — `@Serializable` DTOs: `SessionSnapshot` (layout / fontSize / focusedPaneIndex / window / panes), `WindowSnapshot`, `PaneSnapshot`, `TabSnapshot` (path, language, cursor, showPreview, draftPath, diskMtime). Plus the non-serializable `SessionWrite` carrier (snapshot + draft texts).
  - `SessionStore.kt` — debounced (800 ms default) atomic JSON persistence to an OS-specific app data dir: `~/Library/Application Support/APV Editor` (macOS), `%APPDATA%/APV Editor` (Windows), `$XDG_DATA_HOME/apv-editor` (else). `schedule(producer)` keeps only the latest producer; `flush()` runs it synchronously (call on shutdown).

State model: one `EditorState` instance per window owns a `mutableStateListOf<TabState>`. Each `TabState` carries a `TextFieldValue`, the backing `File?`, and a `savedText` snapshot used for the dirty marker (`●` prefix in the tab title). `EditorState.openFile` reuses the current tab only when it is empty/unsaved/untitled — otherwise it opens a new tab.

Highlighting bridge: `commonMain` produces `List<Token>` from raw text. `HighlightTransformation` (in `desktopMain`) is a Compose `VisualTransformation` that runs the highlighter on every edit and applies `SpanStyle`s from `EditorTheme.styleFor(TokenKind)` via an `AnnotatedString.Builder`. It uses `OffsetMapping.Identity`, so token ranges must always stay within `text.length` — the transformation already clamps, keep that invariant if you touch it. To add a language: add an enum value in `SyntaxLanguage`, map extensions in `SyntaxDetector.extensionMap`, and add a `LangSpec` (or dedicated `highlight…` function) in `SyntaxHighlighter`.

Autosave: `AutoSaver` debounces with a single-thread daemon `ScheduledExecutorService` (default 1500 ms). `EditorPane` calls `state.scheduleAutosave(tab)` from a `snapshotFlow { tab.textField.text }.distinctUntilChanged()` collector, and `scheduleAutosave` no-ops for unsaved-as-yet (`file == null`) or clean tabs. The scheduled action runs off the UI thread — only touch Compose state through snapshot-safe writes if you extend it.

Session persistence: `SessionStore` writes a JSON snapshot of window/pane/tab layout plus dirty buffer texts to the OS-specific app data dir above. Writes are debounced and atomic (tmp file → `Files.move` with `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING`). `EditorState` produces a `SessionWrite` (snapshot + map of `draftPath` → buffer text). Drafts for dirty tabs live as side files under `drafts/`; orphans are pruned each write. Distinct from autosave — autosave persists only *saved, named* files in place; session persistence captures everything (including unsaved/untitled buffers) so the editor can restore state on relaunch.

Theme: `EditorTheme` is a hand-rolled dark palette (object, not a `CompositionLocal`). Token colors live there too — change `styleFor` to retheme highlighting, not the highlighter itself.
