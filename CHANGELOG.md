# Change Log

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [Unreleased] - 2.0.0

The redesign. Upgrading from 1.5.3: the app no longer reopens the previous
session's tabs. It starts on the start screen, where unsaved buffers from the
last session are listed and can be reopened. Markdown preview moved from its
old shortcut to Cmd/Ctrl+Shift+V.

### Added

- MAJOR Start screen shown while no tab is open: actions, unsaved buffers
  (first 5, then "Show all"), recent folders and recent files.
- MINOR Command palette on Cmd/Ctrl+Shift+P.
- MINOR Find bar with plain-text and regex search (normal files only).
- MINOR Project sidebar, shown once a folder is opened; dropping a folder on
  the window opens it as the project.
- MINOR Minimap for normal files.
- MINOR Syntax language menu and format commands in the command palette.
- MINOR Unified title bar on macOS, with the tab strip beside the traffic
  lights. Windows keeps its native title bar.
- MINOR Current-line highlight and accent-coloured line number in both
  editors.
- MINOR Windows build workflow and file associations for packaged builds.

### Changed

- MAJOR Closing the last tab returns to the start screen instead of quitting,
  and the previous session is no longer restored into tabs on launch.
- MINOR New theme: Material Dark Soda palette with a gold accent, applied to
  dialogs, menus and buttons as well as the editor.
- MINOR Tab titles are the file name or "Untitled N" and are never taken from
  the buffer's content; the content preview is only the tab's hover tooltip.
- MINOR Menu shortcuts use Ctrl on Windows and Linux instead of the Windows
  key.
- PATCH Wheel and trackpad scrolling in both editors moves one axis per
  gesture.

### Fixed

- PATCH Vertical scrolling lagged, stalled and jumped after scrolling a file
  to the right.
- PATCH Large files: the view jumped back to the caret's column when the
  caret's line scrolled out of view and back in.

## [1.5.3] - 2026-09-07

### Added

- MINOR Large-file editor: files too big for the normal editor open in a
  line-indexed view.
- MINOR Autosave modes: off, after a delay, on tab focus change, on window
  focus change.
- PATCH Incremental syntax highlighting, so an edit re-tokenizes from the
  edited region instead of the whole document.

### Changed

- PATCH Line gutter renders only the visible lines.
- PATCH Shared scrollbar style for both editors.

### Fixed

- PATCH Scrolling in large files.

## [1.0.0] - 2026-09-04

### Added

- Initial release: Compose Multiplatform Desktop text editor with tabs, split
  panes, syntax highlighting, autosave, Markdown preview and session restore;
  macOS DMG packaging with Finder "Open With" integration.
