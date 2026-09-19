package com.xiufeigo.opencodeshell

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.languages.Prism_clike
import io.noties.prism4j.languages.Prism_go
import io.noties.prism4j.languages.Prism_javascript
import io.noties.prism4j.languages.Prism_json
import io.noties.prism4j.languages.Prism_kotlin
import io.noties.prism4j.languages.Prism_markdown
import io.noties.prism4j.languages.Prism_markup
import io.noties.prism4j.languages.Prism_python
import io.noties.prism4j.languages.Prism_sql
import io.noties.prism4j.languages.Prism_yaml

/**
 * Hand-rolled prism4j grammar locator (the upstream bundler generates one via
 * kapt; we avoid the annotation processor). Languages are vendored verbatim
 * from noties/Prism4j v2.0.0 under io/noties/prism4j/languages. Unknown or
 * unsupported info strings (bash, rust, …) return null so fenced code blocks
 * render un-highlighted instead of mis-highlighted.
 */
class OcGrammarLocator : GrammarLocator {

    private val cache = HashMap<String, Prism4j.Grammar>()

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        val name = canonical(language) ?: return null
        // Reentrant: creating one grammar may ask the locator for another
        // (kotlin/javascript/go extend clike, markdown extends markup).
        synchronized(cache) {
            cache[name]?.let { return it }
            return create(prism4j, name)?.also { cache[name] = it }
        }
    }

    override fun languages(): Set<String> = LANGUAGE_NAMES

    private fun create(prism4j: Prism4j, name: String): Prism4j.Grammar? = when (name) {
        "clike" -> Prism_clike.create(prism4j)
        "go" -> Prism_go.create(prism4j)
        "javascript" -> Prism_javascript.create(prism4j)
        "json" -> Prism_json.create(prism4j)
        "kotlin" -> Prism_kotlin.create(prism4j)
        "markdown" -> Prism_markdown.create(prism4j)
        "markup" -> Prism_markup.create(prism4j)
        "python" -> Prism_python.create(prism4j)
        "sql" -> Prism_sql.create(prism4j)
        "yaml" -> Prism_yaml.create(prism4j)
        else -> null
    }

    companion object {
        private val LANGUAGE_NAMES = setOf(
            "clike", "go", "javascript", "json", "kotlin",
            "markdown", "markup", "python", "sql", "yaml",
        )

        private val ALIASES = mapOf(
            "js" to "javascript",
            "jsonp" to "json",
            "xml" to "markup",
            "html" to "markup",
            "mathml" to "markup",
            "svg" to "markup",
            "py" to "python",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "yml" to "yaml",
            "golang" to "go",
        )

        private fun canonical(language: String): String? {
            val name = language.trim().lowercase()
            if (name.isEmpty()) return null
            return ALIASES[name] ?: if (name in LANGUAGE_NAMES) name else null
        }
    }
}
