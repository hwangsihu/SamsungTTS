package com.samsung.SMN.engine;

import com.hwangsihu.smt.engine.LegacyEngine;

// The SM-N910L (Galaxy Note 4) engine (libsmtn910.so) registers its natives here; tools/import_samsung_assets.py renames
// its com/samsung/SMT/engine/SmtTTS class, which the 3.5 engine's loader binds to, to com/samsung/SMN/engine/SmtTTS.
public final class SmtTTS implements LegacyEngine {

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
