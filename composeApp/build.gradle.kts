import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(compose.ui)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("com.mikepenz:multiplatform-markdown-renderer:0.27.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    jvmToolchain(21)
}

compose.resources {
    packageOfResClass = "com.apv.editor"
    publicResClass = true
}

compose.desktop {
    application {
        mainClass = "com.apv.editor.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "APV Editor"
            packageVersion = "1.5.3"
            description = "APV Text Editor"
            vendor = "APV"

            // Applied to Windows MSI (and Linux DEB/RPM). macOS uses CFBundleDocumentTypes
            // in the infoPlist below — the two registration mechanisms do not overlap.
            // Extensions mirror SyntaxDetector.extensionMap.
            listOf(
                "txt" to "text/plain",
                "md" to "text/markdown",
                "markdown" to "text/markdown",
                "kt" to "text/x-kotlin",
                "kts" to "text/x-kotlin",
                "java" to "text/x-java-source",
                "js" to "application/javascript",
                "jsx" to "text/jsx",
                "mjs" to "application/javascript",
                "ts" to "application/typescript",
                "tsx" to "application/typescript",
                "py" to "text/x-python",
                "rb" to "application/x-ruby",
                "go" to "text/x-go",
                "rs" to "text/x-rust",
                "c" to "text/x-c",
                "h" to "text/x-c",
                "cpp" to "text/x-c++",
                "cc" to "text/x-c++",
                "hpp" to "text/x-c++",
                "cs" to "text/x-csharp",
                "swift" to "text/x-swift",
                "html" to "text/html",
                "htm" to "text/html",
                "xml" to "application/xml",
                "json" to "application/json",
                "yaml" to "application/yaml",
                "yml" to "application/yaml",
                "toml" to "application/toml",
                "sh" to "application/x-sh",
                "bash" to "application/x-sh",
                "zsh" to "application/x-sh",
                "sql" to "application/sql",
                "css" to "text/css",
                "scss" to "text/x-scss",
                "less" to "text/x-less",
                "php" to "application/x-php",
                "groovy" to "text/x-groovy",
                "gradle" to "text/x-groovy",
                "lua" to "text/x-lua",
                "scala" to "text/x-scala",
                "clj" to "text/x-clojure",
            ).forEach { (ext, mime) ->
                fileAssociation(
                    extension = ext,
                    mimeType = mime,
                    description = "APV Editor document",
                )
            }

            macOS {
                bundleID = "com.apv.editor"
                iconFile.set(project.file("launcher/AppIcon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleDocumentTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>Text Document</string>
                                <key>CFBundleTypeRole</key>
                                <string>Editor</string>
                                <key>LSHandlerRank</key>
                                <string>Alternate</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>public.text</string>
                                    <string>public.plain-text</string>
                                    <string>public.source-code</string>
                                    <string>public.script</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }

            windows {
                iconFile.set(project.file("launcher/AppIcon.ico"))
                menuGroup = "APV Editor"
                // Stable across versions — changing this would orphan prior installs
                // instead of upgrading them.
                upgradeUuid = "407318C1-8E6A-4B18-BB6F-D9F06768BE66"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
