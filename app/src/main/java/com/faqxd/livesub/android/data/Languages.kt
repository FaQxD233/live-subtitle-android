package com.faqxd.livesub.android.data

/**
 * Supported target languages (official Live Translation list, see
 * ai.google.dev/gemini-api/docs/live-api/live-translate).
 *
 * `code` is the BCP-47 language code sent to the Gemini Live API
 * `translationConfig.targetLanguageCode` field.
 *
 * Simplified Chinese keeps the legacy `zh-CN` code on purpose (rather than
 * the documented `zh-Hans`) so the model consistently returns Simplified
 * Chinese; [AppSettings.normalizeTargetLanguage] maps the documented
 * spellings onto it.
 */
data class TranslationLanguage(val code: String, val name: String)

object Languages {
    val ALL: List<TranslationLanguage> = listOf(
        TranslationLanguage("af", "Afrikaans"),
        TranslationLanguage("ak", "Akan"),
        TranslationLanguage("sq", "Albanian"),
        TranslationLanguage("am", "Amharic"),
        TranslationLanguage("ar", "Arabic"),
        TranslationLanguage("hy", "Armenian"),
        TranslationLanguage("az", "Azerbaijani"),
        TranslationLanguage("eu", "Basque"),
        TranslationLanguage("be", "Belarusian"),
        TranslationLanguage("bn", "Bengali"),
        TranslationLanguage("bg", "Bulgarian"),
        TranslationLanguage("my", "Burmese (Myanmar)"),
        TranslationLanguage("ca", "Catalan"),
        TranslationLanguage("zh-CN", "Chinese (Simplified)"),
        TranslationLanguage("zh-Hant", "Chinese (Traditional)"),
        TranslationLanguage("hr", "Croatian"),
        TranslationLanguage("cs", "Czech"),
        TranslationLanguage("da", "Danish"),
        TranslationLanguage("nl", "Dutch"),
        TranslationLanguage("en", "English"),
        TranslationLanguage("et", "Estonian"),
        TranslationLanguage("fil", "Filipino"),
        TranslationLanguage("fi", "Finnish"),
        TranslationLanguage("gl", "Galician"),
        TranslationLanguage("ka", "Georgian"),
        TranslationLanguage("de", "German"),
        TranslationLanguage("el", "Greek"),
        TranslationLanguage("gu", "Gujarati"),
        TranslationLanguage("ha", "Hausa"),
        TranslationLanguage("he", "Hebrew"),
        TranslationLanguage("hi", "Hindi"),
        TranslationLanguage("hu", "Hungarian"),
        TranslationLanguage("is", "Icelandic"),
        TranslationLanguage("id", "Indonesian"),
        TranslationLanguage("it", "Italian"),
        TranslationLanguage("ja", "Japanese"),
        TranslationLanguage("jv", "Javanese"),
        TranslationLanguage("kn", "Kannada"),
        TranslationLanguage("kk", "Kazakh"),
        TranslationLanguage("km", "Khmer"),
        TranslationLanguage("rw", "Kinyarwanda"),
        TranslationLanguage("ko", "Korean"),
        TranslationLanguage("lo", "Lao"),
        TranslationLanguage("lv", "Latvian"),
        TranslationLanguage("lt", "Lithuanian"),
        TranslationLanguage("mk", "Macedonian"),
        TranslationLanguage("ms", "Malay"),
        TranslationLanguage("ml", "Malayalam"),
        TranslationLanguage("mr", "Marathi"),
        TranslationLanguage("mn", "Mongolian"),
        TranslationLanguage("ne", "Nepali"),
        TranslationLanguage("no", "Norwegian"),
        TranslationLanguage("nb", "Norwegian Bokmål"),
        TranslationLanguage("fa", "Persian"),
        TranslationLanguage("pl", "Polish"),
        TranslationLanguage("pt-BR", "Portuguese (Brazil)"),
        TranslationLanguage("pt-PT", "Portuguese (Portugal)"),
        TranslationLanguage("pa", "Punjabi"),
        TranslationLanguage("ro", "Romanian"),
        TranslationLanguage("ru", "Russian"),
        TranslationLanguage("sr", "Serbian"),
        TranslationLanguage("sd", "Sindhi"),
        TranslationLanguage("si", "Sinhala"),
        TranslationLanguage("sk", "Slovak"),
        TranslationLanguage("sl", "Slovenian"),
        TranslationLanguage("es", "Spanish"),
        TranslationLanguage("su", "Sundanese"),
        TranslationLanguage("sw", "Swahili"),
        TranslationLanguage("sv", "Swedish"),
        TranslationLanguage("ta", "Tamil"),
        TranslationLanguage("te", "Telugu"),
        TranslationLanguage("th", "Thai"),
        TranslationLanguage("tr", "Turkish"),
        TranslationLanguage("uk", "Ukrainian"),
        TranslationLanguage("ur", "Urdu"),
        TranslationLanguage("uz", "Uzbek"),
        TranslationLanguage("vi", "Vietnamese"),
        TranslationLanguage("zu", "Zulu"),
    )

    fun nameFor(code: String): String =
        ALL.firstOrNull { it.code == code }?.name ?: code.uppercase()

    /**
     * Compact badge label. Region-qualified codes keep their region
     * (`pt-BR` -> "PT-BR") so Brazilian and European Portuguese stay
     * distinguishable; the two Chinese variants get hanzi glyphs.
     */
    fun shortNameFor(code: String): String =
        when (code) {
            "zh-CN" -> "中"
            "zh-Hant" -> "繁"
            else -> code.uppercase()
        }
}
