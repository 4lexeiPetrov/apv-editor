# APV Editor

A Compose Multiplatform Desktop text editor with live syntax highlighting, autosave, multi-tab editing, Markdown preview, and session restore. macOS-first (DMG packaging + Finder "Open With…" integration), portable to other JVM desktops.

## Stack

- Kotlin 2.0.21 (multiplatform), JVM toolchain 21
- Compose Multiplatform 1.7.3 (`material3`, `materialIconsExtended`, `components.resources`)
- `kotlinx-serialization-json` 1.7.3 — session persistence
- `kotlinx-coroutines-swing` 1.9.0
- `com.mikepenz:multiplatform-markdown-renderer{,-m3}` 0.27.0 — preview pane
- Gradle 9.5.1 (wrapper)

## Quick start

```bash
./gradlew :composeApp:run
```

In IntelliJ, the **Run Desktop** configuration under `.run/` does the same thing.

## Build and package

```bash
./gradlew :composeApp:build        # compile + check
./gradlew :composeApp:packageDmg   # macOS .dmg
```

The `.dmg` is produced at `composeApp/build/compose/binaries/main/dmg/`. The bundle is signed as `com.apv.editor` and declares `CFBundleDocumentTypes` for text/source files, so right-click → **Open With → APV Editor** in Finder routes the files into the running app.

## Project layout

Single-module Kotlin Multiplatform project. Matches the [JetBrains KMP wizard](https://kmp.jetbrains.com/) output with **Share UI** enabled, restricted to the desktop target.

- `composeApp/src/commonMain/kotlin/` — pure-Kotlin syntax pipeline (`SyntaxLanguage`, `SyntaxDetector`, `SyntaxHighlighter`). No Compose, no JVM I/O.
- `composeApp/src/desktopMain/kotlin/` — Compose Desktop UI, AWT integration, `java.io`, session persistence.
- `composeApp/launcher/` — macOS app icon (`AppIcon.icns`) and the script that builds it.
- `.run/` — IntelliJ run configurations shared via VCS.

See [CLAUDE.md](CLAUDE.md) for architecture details (state model, highlighting bridge, autosave, session restore).

## License

Apache License 2.0 — see [LICENSE.txt](LICENSE.txt).
