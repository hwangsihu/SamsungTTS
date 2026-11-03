package com.samsung.SMT;

import com.hwangsihu.smt.engine.LegacyEngine;

// The 2014 Note 2 engine (libsmt2014.so) registers its natives on this class by name in JNI_OnLoad;
// keep the package, class name and every native below.
public final class SamsungTTSService implements LegacyEngine {

    @Override
    public native int initialize();

    public native int terminate();

    @Override
    public native int setLanguage(String dataDir, String language, String country, String voiceType,
                                  int voiceNumber, int dictionary);

    public native int getIsLanguageAvailable(String dataDir, String language, String country, String voiceType,
                                             int voiceNumber, int dictionary);

    @Override
    public native int setParameters(int speed, int pitch, float spectrum, int effect);

    @Override
    public native int speak(String text);

    @Override
    public native int getSynthesizedData(byte[] buffer);

    public native boolean supervisor_GetUtterance(String[] utterance);

    @Override
    public native void supervisor_InputText(String text, String language, String country);

    @Override
    public boolean nextUtterance(String[] utterance) {
        return supervisor_GetUtterance(utterance);
    }

    @Override
    public int samplingRate() {
        return 16000;
    }
}
