package com.hwangsihu.smt.engine

interface LegacyEngine {
    fun initialize(): Int

    fun setLanguage(dataDir: String, language: String, country: String, voiceType: String, voiceNumber: Int, dictionary: Int): Int

    fun setParameters(speed: Int, pitch: Int, spectrum: Float, effect: Int): Int

    fun speak(text: String): Int

    fun getSynthesizedData(buffer: ByteArray): Int

    fun supervisor_InputText(text: String, language: String, country: String)

    // Takes the next utterance of the text given to supervisor_InputText; false once none are left.
    fun nextUtterance(utterance: Array<String?>): Boolean

    fun samplingRate(): Int
}
