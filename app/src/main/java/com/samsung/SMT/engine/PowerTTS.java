package com.samsung.SMT.engine;

import com.samsung.SMT.ref.VoiceDataInfo;

// libsamsungxtts.so from the HD voice pack binds to these names; keep them as they are.
public final class PowerTTS {

    public static final int BUFFER_SIZE = 160;

    public PowerTTS(String libraryPath) {
        System.load(libraryPath);
    }

    public native int initialize();

    public native int terminate();

    public native int getVersion();

    public native int getSamplingRate();

    public native int setLanguageWithFD(VoiceDataInfo[] files);

    public native int setParameters(int speed, int pitch, float volume, int reserved);

    public native int speak(String text);

    public native int getSynthesizedData(byte[] buffer);
}
