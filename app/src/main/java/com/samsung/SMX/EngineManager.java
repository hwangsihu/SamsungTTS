package com.samsung.SMX;

// The SM-P600 engine registers these natives on this class by name; keep them as they are.
public final class EngineManager {

    public native int getEngineVersion();

    public native int getIsLanguageAvailable(String dataDir, String language, String country, String voiceType,
                                             int voiceNumber, int dictionary);
}
