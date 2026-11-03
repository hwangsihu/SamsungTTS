package com.samsung.SMT;

// The 2014 Note 2 engine (libsmt2014.so) registers its natives on this class by name in JNI_OnLoad;
// keep the package, class name and every method below.
public final class SamsungTTSService {

    public native int initialize();

    public native int terminate();

    public native int setLanguage(String dataDir, String language, String country, String voiceType,
                                  int voiceNumber, int dictionary);

    public native int getIsLanguageAvailable(String dataDir, String language, String country, String voiceType,
                                             int voiceNumber, int dictionary);

    public native int setParameters(int speed, int pitch, float alpha, int effect);

    public native int speak(String text);

    public native int getSynthesizedData(byte[] buffer);

    public native boolean supervisor_GetUtterance(String[] utterance);

    public native void supervisor_InputText(String text, String language, String country);
}
