# Samsung TTS

An Android text-to-speech engine that brings the voices of older Galaxy devices (Galaxy Note 2, Galaxy S4, Galaxy Note 10.1 2014 Edition, Galaxy Note 4, Galaxy J5 and Galaxy Note 8) to current Galaxy phones, tablets and watches, and to Android devices from other makers. The Note 2, S4, Note 10.1, Note 4 and J5 voices run on their devices' own engines, so they keep the intonation they had there.

> [!IMPORTANT]
> This project is not affiliated with or endorsed by Samsung. The repository contains no Samsung code or voice data: the engines and voices are imported at build time from firmware and APKs you provide.

## Features

- **Embedded voices** (female, and male where noted):

  | Source device | Engine | Languages |
  | --- | --- | --- |
  | Galaxy Note 2 (SHV-E250L) | its own engine | de-DE, en-US, es-ES, fr-FR, it-IT, ja-JP, ko-KR (also male), pt-PT, ru-RU, zh-CN |
  | Galaxy S4 (SHV-E300L) | its own engine | de-DE, en-GB, en-US, es-ES, fr-FR, it-IT, ja-JP, ko-KR (also male), pt-BR, pt-PT, ru-RU, zh-CN |
  | Galaxy Note 10.1 2014 Edition (SM-P600) | its own engine | same as Note 2 |
  | Galaxy Note 4 (SM-N910L) | its own engine | de-DE, en-GB, en-US, es-ES, es-MX, fr-FR, hi-IN, it-IT, ja-JP, ko-KR (also male), pt-BR, pt-PT, ru-RU, zh-CN |
  | Galaxy J5 (SM-J500N0) | its own engine | en-US, ko-KR |
  | Galaxy Note 8 (SM-N950N) | Samsung TTS 3.5 | ko-KR, en-US |

  The J5 also ships the Korean male voice, but it speaks exactly like the Note 4 one, so only that one is included.

- **Installed voice packs** (`com.samsung.SMT.lang_*`): standard, unit-concatenation, neural (including multi-speaker and multilingual) and HD packs appear next to the embedded voices and are spoken the way Samsung's own TTS speaks them. The older HD Korean packs (`lang_ko_kr_x01` from before Samsung TTS 3.5) run on the Note 4 engine, read straight from the pack.
- **64-bit-only phones**: the 2014 engines are 32-bit ARM code. On devices that can no longer run 32-bit code, the app runs them in a built-in ARM emulator based on [dynarmic](https://github.com/azahar-emu/dynarmic).
- **Phones, tablets, watches and TVs**, from one APK. In wide windows (tablets, foldables, Chromebooks, TVs) a language's voices open next to the language list. Wear OS watches get their own screens with rotary scrolling and swipe to go back, and Android TV lists the app in its launcher and drives it with the D-pad.
- A default voice per language. Apps that only choose a language, such as TalkBack, follow a new choice without a restart.
- FP16 for neural voices on devices that support it.
- The preview in Android's text-to-speech settings speaks Android's standard sample sentences.
- English or Korean, chosen per app on Android 13 and later.
- Android 7.0 (API 24) or later.

## How it works

| Engine | Voices | Library |
| --- | --- | --- |
| Samsung TTS 3.5 | Note 8 and voice packs | `libsmtloader.so` and `libsamsungttse.so` from the Samsung TTS 3.5 APK, or a pack's own engine |
| Note 2, S4, SM-P600, Note 4, J5 | that device's voices | each device's `libsamsungtts.so`, from its firmware |
| HD | HD packs | the pack's own library |

The device engines are 32-bit ARM code. On devices that can run 32-bit code, the app runs as a 32-bit process and loads them directly, with small stand-ins for the platform libraries the Note 2 and SM-P600 engines expect (`app/src/main/cpp`). In a 64-bit process, `libsmtemu.so` (`app/src/main/cpp/emu`) loads the 32-bit engine into an emulated address space, provides the C library and JNI functions it calls, and runs it with dynarmic. Either way the engines read their voice files straight from the APK, without copies on disk.

## Samsung files

Put these in one folder, in any layout:

- The firmware of six devices from [SamFW](https://samfw.com): the zips as downloaded, their unpacked folders, or just their AP files (`AP_….tar.md5`). These are the builds the voices were imported from; other builds of the same models have not been tried.

  | Device | Model | Firmware |
  | --- | --- | --- |
  | Galaxy Note 8 | SM-N950N | [N950NKSU5DZE2](https://samfw.com/firmware/SM-N950N/LUC/N950NKSU5DZE2) |
  | Galaxy Note 2 | SHV-E250L | [E250LKLUKOH4](https://samfw.com/firmware/SHV-E250L/LGT/E250LKLUKOH4) |
  | Galaxy Note 10.1 2014 Edition | SM-P600 | [P600KXUCQA3](https://samfw.com/firmware/SM-P600/KOO/P600KXUCQA3) |
  | Galaxy S4 | SHV-E300L | [E300LKLUGPI1](https://samfw.com/firmware/SHV-E300L/LGT/E300LKLUGPI1) |
  | Galaxy J5 | SM-J500N0 | [J500N0KOU1ARK1](https://samfw.com/firmware/SM-J500N0/KOO/J500N0KOU1ARK1) |
  | Galaxy Note 4 | SM-N910L | [N910LKLU2DVG1](https://samfw.com/firmware/SM-N910L/LUC/N910LKLU2DVG1) |

- The APK of Samsung text-to-speech engine (`com.samsung.SMT`) 3.5.02.16, with both arm64-v8a and armeabi-v7a libraries. It needs Android 13, so none of the firmware above has it. Pull it from a Galaxy device on which Galaxy Store has updated the engine:

  ```sh
  adb shell pm path com.samsung.SMT
  adb pull /data/app/…/base.apk SamsungTTS_3.5.02.16.apk
  ```

  The first command prints the path to pull after `package:`. The copy built into the firmware will not do: on the Galaxy Tab A11 (SM-X133) it is 3.5.02.15 with arm64-v8a libraries only.

`tools/import_samsung_assets.py` takes that folder. From the system image in each AP file it reads the device's voices (`tts/lang_SMT`) and, for all but the Note 8, its engine (`lib/libsamsungtts.so`); the Samsung TTS 3.5 engine comes from the APK.

## Building

You need:

- the Android SDK with API 37 and NDK 30.0.16248370
- CMake and Ninja, on PATH or from Visual Studio with C++ CMake tools for Windows
- Python 3
- the [Samsung files](#samsung-files), in one folder

```sh
git clone --recursive https://github.com/hwangsihu/SamsungTTS.git
cd SamsungTTS
python -I tools/fetch_deps.py
python -I tools/import_samsung_assets.py <folder with the Samsung files>
python -I tools/build_emulator.py
./gradlew assembleDebug
```

- `fetch_deps.py` downloads the Boost headers that dynarmic needs into `third_party/boost`.
- `import_samsung_assets.py` copies the engines and voices from that folder into `app/src/main/jniLibs` and `app/src/main/assets`, which Git ignores.
- `build_emulator.py` builds `libsmtemu.so` for arm64-v8a and x86_64. It takes the NDK from the SDK that `ANDROID_HOME` points to, and CMake and Ninja from PATH, or else from the newest Visual Studio installation with C++ CMake tools for Windows.

To sign builds with your own key, put a `keystore.properties` in the project root (Git ignores it):

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is relative to the project root. Debug and release builds are then both signed with that key, so either installs over the other. If the file is missing or a value is empty, Gradle warns, debug builds use the debug key and release builds are left unsigned.

Install the APK, pick voices in the app, then choose Samsung TTS as the preferred engine in Android's text-to-speech settings; until then, the app offers to open those settings. Voice packs and HD voices are installed as separate APKs (packs that target an old SDK need `adb install --bypass-low-target-sdk-block`). Watches and TVs take the same APK over ADB; where a device's settings have no engine choice, set the engine from ADB:

```sh
adb shell settings put secure tts_default_synth com.hwangsihu.smt
```

## Project layout

```text
app/src/main/java/com/hwangsihu/smt/     app, TTS service, voice discovery and synthesizers
app/src/main/java/com/samsung/           classes the Samsung engines register their JNI methods on
app/src/main/cpp/                        stand-in libraries and voicefs for the device engines; emu/ is the emulator
tools/                                   asset import, dependency fetch and emulator build scripts
third_party/dynarmic/                    dynarmic (submodule)
```

## Third-party code

- [dynarmic](https://github.com/azahar-emu/dynarmic), a submodule: 0BSD. Its bundled libraries carry their own licenses.
- [Boost](https://www.boost.org/) headers, downloaded by `tools/fetch_deps.py` and not committed: Boost Software License 1.0.
