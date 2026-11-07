package com.hwangsihu.smt.engine

// Runs a 32-bit Samsung TTS engine through libsmtemu.so (a dynarmic ARM interpreter) so it works
// in a 64-bit process, on phones that can no longer execute 32-bit code. The image is the engine's
// own .so bytes, shipped as an asset. The native side keeps one engine loaded at a time.
class EmulatedEngine(private val image: ByteArray) : LegacyEngine {

    override fun initialize(): Int = if (nCreate(image)) 0 else -1

    override fun setLanguage(dataDir: String, language: String, country: String, voiceType: String, voiceNumber: Int, dictionary: Int): Int =
        nSetLanguage(dataDir, language, country, voiceType, voiceNumber, dictionary)

    override fun setParameters(speed: Int, pitch: Int, spectrum: Float, effect: Int): Int {
        nSetParameters(speed, pitch, spectrum, effect)
        return 0
    }

    override fun speak(text: String): Int {
        nSpeak(text)
        return 0
    }

    override fun getSynthesizedData(buffer: ByteArray): Int = nGetSynthesizedData(buffer)

    override fun supervisor_InputText(text: String, language: String, country: String) = nSupervisorInput(text, language, country)

    override fun nextUtterance(utterance: Array<String?>): Boolean = nNextUtterance(utterance)

    override fun samplingRate(): Int = nSamplingRate()

    private external fun nCreate(image: ByteArray): Boolean
    private external fun nSetLanguage(dataDir: String, language: String, country: String, voiceType: String, voiceNumber: Int, dictionary: Int): Int
    private external fun nSetParameters(speed: Int, pitch: Int, spectrum: Float, effect: Int)
    private external fun nSpeak(text: String)
    private external fun nGetSynthesizedData(buffer: ByteArray): Int
    private external fun nSupervisorInput(text: String, language: String, country: String)
    private external fun nNextUtterance(utterance: Array<String?>): Boolean
    private external fun nSamplingRate(): Int

    companion object {
        init {
            System.loadLibrary("smtemu")
        }
    }
}
