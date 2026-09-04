package com.apv.editor.syntax

object SyntaxDetector {

    private val extensionMap = mapOf(
        "kt" to SyntaxLanguage.KOTLIN,
        "kts" to SyntaxLanguage.KOTLIN,
        "java" to SyntaxLanguage.JAVA,
        "js" to SyntaxLanguage.JAVASCRIPT,
        "jsx" to SyntaxLanguage.JAVASCRIPT,
        "mjs" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT,
        "tsx" to SyntaxLanguage.TYPESCRIPT,
        "py" to SyntaxLanguage.PYTHON,
        "rb" to SyntaxLanguage.RUBY,
        "go" to SyntaxLanguage.GO,
        "rs" to SyntaxLanguage.RUST,
        "c" to SyntaxLanguage.C_FAMILY,
        "h" to SyntaxLanguage.C_FAMILY,
        "cpp" to SyntaxLanguage.C_FAMILY,
        "cc" to SyntaxLanguage.C_FAMILY,
        "hpp" to SyntaxLanguage.C_FAMILY,
        "cs" to SyntaxLanguage.CSHARP,
        "swift" to SyntaxLanguage.SWIFT,
        "html" to SyntaxLanguage.HTML,
        "htm" to SyntaxLanguage.HTML,
        "xml" to SyntaxLanguage.XML,
        "json" to SyntaxLanguage.JSON,
        "yaml" to SyntaxLanguage.YAML,
        "yml" to SyntaxLanguage.YAML,
        "toml" to SyntaxLanguage.NONE,
        "sh" to SyntaxLanguage.SHELL,
        "bash" to SyntaxLanguage.SHELL,
        "zsh" to SyntaxLanguage.SHELL,
        "sql" to SyntaxLanguage.SQL,
        "md" to SyntaxLanguage.MARKDOWN,
        "markdown" to SyntaxLanguage.MARKDOWN,
        "css" to SyntaxLanguage.CSS,
        "scss" to SyntaxLanguage.CSS,
        "less" to SyntaxLanguage.CSS,
        "php" to SyntaxLanguage.PHP,
        "groovy" to SyntaxLanguage.GROOVY,
        "gradle" to SyntaxLanguage.GROOVY,
        "lua" to SyntaxLanguage.LUA,
        "scala" to SyntaxLanguage.SCALA,
        "clj" to SyntaxLanguage.CLOJURE,
        "txt" to SyntaxLanguage.NONE,
    )

    fun detect(filename: String): SyntaxLanguage {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return extensionMap[ext] ?: SyntaxLanguage.NONE
    }
}
