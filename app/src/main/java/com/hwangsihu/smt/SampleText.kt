package com.hwangsihu.smt

object SampleText {
    private val byIso3 = mapOf(
        "kor" to "안녕하세요. 삼성 TTS 음성입니다.",
        "eng" to "Hello. This is a Samsung TTS voice.",
        "deu" to "Hallo. Dies ist eine Samsung TTS Stimme.",
        "spa" to "Hola. Esta es una voz de Samsung TTS.",
        "fra" to "Bonjour. Ceci est une voix Samsung TTS.",
        "ita" to "Ciao. Questa è una voce di Samsung TTS.",
        "jpn" to "こんにちは。サムスンTTSの音声です。",
        "por" to "Olá. Esta é uma voz do Samsung TTS.",
        "rus" to "Здравствуйте. Это голос Samsung TTS.",
        "zho" to "你好。这是三星TTS语音。",
    )

    fun forLanguage(iso3Language: String?): String = byIso3[iso3Language] ?: byIso3.getValue("eng")
}
