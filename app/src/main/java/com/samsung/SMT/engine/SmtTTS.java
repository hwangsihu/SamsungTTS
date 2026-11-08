package com.samsung.SMT.engine;

import com.samsung.SMT.ref.VoiceDataInfo;

// libsmtloader.so binds to these names and calls AudioCallback by name; keep them as they are.
public final class SmtTTS {

    public interface AudioSink {
        boolean onAudio(byte[] buffer, int offset, int length);
    }

    public static final int TTS_TYPE_HMM = 0;
    public static final int TTS_TYPE_DIC = 1;
    public static final int TTS_TYPE_UC = 2;
    public static final int TTS_TYPE_NEURAL = 3;
    public static final int TTS_TYPE_NEURAL_MULTISPEAKER = 4;
    public static final int TTS_TYPE_NEURAL_MULTILINGUAL = 6;

    public static final int FEATURE_FP16 = 2;

    static {
        System.loadLibrary("smtloader");
    }

    private long handle;
    private volatile AudioSink sink;

    public SmtTTS(int instanceId) {
        handle = initialize(instanceId);
    }

    public boolean isValid() {
        return handle != 0;
    }

    public int loadEngine(String engine) {
        return loadEngine(handle, engine);
    }

    public int unloadEngine() {
        return unloadEngine(handle);
    }

    public int getVersion() {
        return getVersion(handle);
    }

    public boolean isSupportMultiInstance() {
        return isSupportMultiInstance(handle);
    }

    public int getSamplingRate() {
        return getSamplingRate(handle);
    }

    public int setLanguageWithFD(VoiceDataInfo[] files, String language, String country, String voiceType,
                                 int voiceNumber, int ttsType, int flags) {
        return setLanguageWithFD(handle, files, language, country, voiceType, voiceNumber, ttsType, flags);
    }

    public int setVoiceControls(int speed, int pitch, float volume, int reserved) {
        return setVoiceControls(handle, speed, pitch, volume, reserved);
    }

    public void setMaxBufferSize(int bytes) {
        setMaxBufferSize(handle, bytes);
    }

    public int speak(String text, AudioSink sink) {
        this.sink = sink;
        try {
            return speak(handle, text);
        } finally {
            this.sink = null;
        }
    }

    // Multilingual neural voices are spoken with their locale, such as "ko-KR".
    public int speakWithLocales(String text, String locale, AudioSink sink) {
        this.sink = sink;
        try {
            return speakWithLocales(handle, text, locale, null, "");
        } finally {
            this.sink = null;
        }
    }

    public void stopSpeak() {
        stopSpeak(handle);
    }

    public void release() {
        if (handle != 0) {
            terminate(handle);
            handle = 0;
        }
    }

    @SuppressWarnings("unused")
    private void AudioCallback(int instanceId, byte[] pcm, int textOffset, int textLength, String text, int result) {
        AudioSink s = sink;
        if (s != null && pcm != null && pcm.length > 0 && !s.onAudio(pcm, 0, pcm.length)) {
            sink = null;
        }
    }

    private native long initialize(int instanceId);

    private native int loadEngine(long handle, String engine);

    private native int unloadEngine(long handle);

    private native int getVersion(long handle);

    private native boolean isSupportMultiInstance(long handle);

    private native int speakWithLocales(long handle, String text, String locale, String[] locales, String extra);

    private native int getSamplingRate(long handle);

    private native int setLanguageWithFD(long handle, VoiceDataInfo[] files, String language, String country,
                                         String voiceType, int voiceNumber, int ttsType, int flags);

    private native int setVoiceControls(long handle, int speed, int pitch, float volume, int reserved);

    private native void setMaxBufferSize(long handle, int bytes);

    private native int speak(long handle, String text);

    private native void stopSpeak(long handle);

    private native int terminate(long handle);
}
