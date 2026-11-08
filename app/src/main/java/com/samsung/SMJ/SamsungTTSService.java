package com.samsung.SMJ;

import com.hwangsihu.smt.engine.LegacyEngine;

// The SM-J500N0 (Galaxy J5) engine (libsmtj500.so) registers its natives here; tools/import_samsung_assets.py renames
// its com/samsung/SMT classes to com/samsung/SMJ so it can share a process with the other engines.
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

    // The original service sets otherEngine only for its "l" and "z" voices, which a separate engine speaks.
    public native boolean supervisor_GetUtterance(String[] utterance, boolean otherEngine);

    @Override
    public native void supervisor_InputText(String text, String language, String country);

    @Override
    public boolean nextUtterance(String[] utterance) {
        return supervisor_GetUtterance(utterance, false);
    }

    private native int getSamplingRate();

    @Override
    public int samplingRate() {
        return getSamplingRate();
    }
}
