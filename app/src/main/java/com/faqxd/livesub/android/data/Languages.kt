package com.faqxd.livesub.android.data

/**
 * Supported target languages (mirrors `settings.py:LANGUAGES`).
 *
 * `code` is the BCP-47 language code sent to the Gemini Live API
 * `translationConfig.targetLanguageCode` field. Chinese uses `zh-CN`
 * rather than bare `zh` so the model consistently returns Simplified
 * Chinese instead of choosing script from context.
 */
data class TranslationLanguage(val code: String, val name: String)

object Languages {
    val ALL: List<TranslationLanguage> = listOf(
        TranslationLanguage("en", "English"),
        TranslationLanguage("es", "Spanish"),
        TranslationLanguage("fr", "French"),
        TranslationLanguage("de", "German"),
        TranslationLanguage("it", "Italian"),
        TranslationLanguage("ja", "Japanese"),
        TranslationLanguage("ko", "Korean"),
        TranslationLanguage("zh-CN", "Chinese (Simplified)"),
        TranslationLanguage("vi", "Vietnamese"),
        TranslationLanguage("pt", "Portuguese"),
        TranslationLanguage("ru", "Russian"),
        TranslationLanguage("hi", "Hindi"),
        TranslationLanguage("ar", "Arabic"),
        TranslationLanguage("th", "Thai"),
        TranslationLanguage("id", "Indonesian"),
        TranslationLanguage("tr", "Turkish"),
    )

    fun nameFor(code: String): String =
        ALL.firstOrNull { it.code == code }?.name ?: code.uppercase()

    fun shortNameFor(code: String): String =
        when (code) {
            "zh-CN" -> "中"
            else -> code.substringBefore('-').uppercase()
        }
}
