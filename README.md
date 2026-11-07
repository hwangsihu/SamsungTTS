# Samsung TTS

An Android text-to-speech engine that brings the voices of older Galaxy devices (Galaxy Note 2, Galaxy Note 10.1 2014 Edition and Galaxy Note 8) to current Galaxy phones and to Android devices from other makers. The Note 2 and SM-P600 voices run on their original 2014 engines, so they keep the intonation they had on those devices.

> [!IMPORTANT]
> This project is not affiliated with or endorsed by Samsung. The repository contains no Samsung code or voice data: the engines and voices are imported at build time from firmware and APKs you provide.

## Features

- **Embedded voices** (female):

  | Source device | Engine | Languages |
  | --- | --- | --- |
  | Galaxy Note 2 (SHV-E250L) | 2014 engine | de-DE, en-US, es-ES, fr-FR, it-IT, ja-JP, ko-KR, pt-PT, ru-RU, zh-CN |
  | Galaxy Note 10.1 2014 Edition (SM-P600) | its own 2014 engine | same as Note 2 |
  | Galaxy Note 8 (SM-N950N) | Samsung TTS 3.5 | ko-KR, en-US |

- **Installed voice packs** (`com.samsung.SMT.lang_*`): standard, neural (including multi-speaker) and HD packs appear next to the embedded voices, as they did in Samsung's own TTS.
- **64-bit-only phones**: the 2014 engines are 32-bit ARM code. On devices that can no longer run 32-bit code, the app runs them in a built-in ARM emulator based on [dynarmic](https://github.com/azahar-emu/dynarmic).
- A default voice per language. Apps that only choose a language, such as TalkBack, follow a new choice without a restart.
- FP16 for neural voices on devices that support it.
- The preview in Android's text-to-speech settings speaks Android's standard sample sentences.
- Android 7.0 (API 24) or later.

## How it works

| Engine | Voices | Library |
| --- | --- | --- |
| Samsung TTS 3.5 | Note 8, standard and neural packs | `libsmtloader.so` and `libsamsungttse.so` from the Samsung TTS 3.5 APK, or a pack's own engine |
| 2014 (Note 2) | Note 2 | `libsamsungtts.so` from the Note 2 firmware |
| 2014 (SM-P600) | SM-P600 | `libsamsungtts.so` from the SM-P600 firmware |
| HD | HD packs | the pack's own library |

On devices that can run 32-bit code, the app runs as a 32-bit process and loads the 2014 engines directly, with small stand-ins for the platform libraries they expect (`app/src/main/cpp`). In a 64-bit process, `libsmtemu.so` (`app/src/main/cpp/emu`) loads the 32-bit engine into an emulated address space, provides the C library and JNI functions it calls, and runs it with dynarmic.

## Building

The scripts assume Windows. You need:

- the Android SDK with API 37 and NDK 30.0.16248370
- Visual Studio Build Tools 18, whose CMake and Ninja build the emulator
- Python 3

```sh
git clone --recursive https://github.com/hwangsihu/SamsungTTS.git
cd SamsungTTS
python -I tools/fetch_deps.py
python -I tools/import_samsung_assets.py <Samsung TTS 3.5 APK> <Note 8 tts/lang_SMT> <Note 2 system> <SM-P600 system>
python -I tools/build_emulator.py
./gradlew assembleDebug
```

- `fetch_deps.py` downloads the Boost headers that dynarmic needs into `third_party/boost`.
- `import_samsung_assets.py` copies the engines and voices into `app/src/main/jniLibs` and `app/src/main/assets`, which Git ignores. The `system` arguments are the extracted system partitions of each device's firmware.
- `build_emulator.py` builds `libsmtemu.so` for arm64-v8a and x86_64.

Install the APK, pick voices in the app, then choose Samsung TTS as the preferred engine in Android's text-to-speech settings. Voice packs and HD voices are installed as separate APKs.

## Project layout

```text
app/src/main/java/com/hwangsihu/smt/     app, TTS service, voice discovery and synthesizers
app/src/main/java/com/samsung/SMT, SMX/  classes the Samsung engines register their JNI methods on
app/src/main/cpp/                        stand-in libraries for the 2014 engines; emu/ is the emulator
tools/                                   asset import, dependency fetch and emulator build scripts
third_party/dynarmic/                    dynarmic (submodule)
```

## Third-party code

- [dynarmic](https://github.com/azahar-emu/dynarmic), a submodule: 0BSD. Its bundled libraries carry their own licenses.
- [Boost](https://www.boost.org/) headers, downloaded by `tools/fetch_deps.py` and not committed: Boost Software License 1.0.
