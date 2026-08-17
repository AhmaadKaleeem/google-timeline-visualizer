package dev.mahlernim.timelinevisualizer.model

object TitleTemplate {
    fun resolve(template: String, year: Int, name: String, fallback: String): String {
        val resolved = template
            .replace("{year}", year.toString(), ignoreCase = true)
            .replace("{name}", name.trim(), ignoreCase = true)
            .trim()
        return resolved.ifBlank { fallback }
    }
}
