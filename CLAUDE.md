# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

An Android TTS engine (`com.hwangsihu.smt`) that runs Samsung's older TTS engines and voices (Galaxy Note 2, S4, Note 10.1 2014 (SM-P600), Note 4 (SM-N910L), J5 and Note 8, plus installed `com.samsung.SMT.lang_*` packs) on current phones. README.md has the user-facing description. The repository is public and holds no Samsung binaries or voice data; those are imported locally into gitignored folders.

## Commands

```sh
git submodule update --init --recursive
python -I tools/fetch_deps.py        # Boost headers into third_party/boost (gitignored)
python -I tools/import_samsung_assets.py <3.5 APK> <Note 8 lang_SMT> <Note 2 system> <SM-P600 system> <SHV-E300L system> <SM-J500N0 system> <SM-N910L system>
python -I tools/build_emulator.py    # libsmtemu.so for arm64-v8a and x86_64, into jniLibs
./gradlew assembleDebug
./gradlew build                      # also runs lint; lint errors fail the build
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk
```

Debug and release builds are both signed with the key that `keystore.properties` in the root names (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is relative to the root). The file is gitignored; if it is missing or a value is empty, Gradle warns, debug builds use the debug key and release builds are left unsigned.

There are no unit tests. Changes are verified by synthesizing speech on a device (see Testing). CI (`.github/workflows/build.yml`) runs `./gradlew build` and `tools/build_emulator.py` on Ubuntu without any Samsung files, so it only checks that everything compiles and passes lint.

## Architecture

- `SmtTtsService` is the `TextToSpeechService`. `VoiceRepository` lists embedded voices and installed packs, found through `com.samsung.SMT.action.CHECK_VOICE_DATA` providers, plus the older HD packs that have none (`OLD_HD_PACKS`, also in the manifest's `<queries>`), whose `assets/DATA/smt_ko_KR_x01.am/` folder the Note 4 engine reads. Embedded voices live in `assets/voices/<device>/<lang>_<variant>` with the device folders `Note2`, `Note8`, `Note10.1`, `Note4`, `S4` and `J5`; their names are `<device>-<lang>-<COUNTRY>-<variant>` (`S4-ko-KR-f00`, `Note4-ko-KR-m00` for a male voice), and the list follows the folders' name order. A device may read model files it shares with other devices from their folders (`Device.sharesWith`: SM-P600 from Note 2, Note 4 from S4 and Note 8); the import script leaves those files out. A voice that speaks exactly like another device's (same PCM) is not imported, such as the J5's `ko_KR_m00`, which matches the Note 4's. Pack metadata decides HD, neural or standard; multi-speaker packs (`voice.data.supported_voices`) give a voice per speaker, multilingual ones (also `voice.data.supported_locales`) a voice per speaker and locale, `voice.data.default_voice` first. Per-language alias voices (`ko-default` and so on) let language-only clients such as TalkBack follow the default chosen in the app.
- `MainActivity` keeps two screens, the languages and one language's voices, on a Navigation 3 back stack (`Screen`); the language list offers the text-to-speech settings while this is not the preferred engine. The UI is English or Korean, and `generateLocaleConfig` with `localeFilters` makes that choosable per app. Phones, tablets and TVs show it through Material 3's list-detail scene, with two panes from the expanded width class up; TVs also get overscan margins, a dark theme and focus outlines. Watches, picked by the watch UI mode, show it through `WatchScreens.kt` with Wear Compose and its swipe-to-dismiss scene. Wear Compose needs API 25, so the manifest overrides its minSdk; only watches load it.
- `Synthesizer.load` picks the engine:
  - `HdSynthesizer`: HD packs, through `PowerTTS` and the pack's own library.
  - `LegacySynthesizer`: Note 2, SM-P600, S4, Note 4 and J5 voices, through each device's own engine (`LegacySynthesizer.Kind`, chosen by the device folder).
  - `ClassicSynthesizer`: everything else, through the 3.5 engine (`SmtTTS`: `libsmtloader.so` loads `libsamsungttse.so`) or a pack's own `libsamsungtts.so`. It follows Samsung TTS 3.5.02.16: the voice letter sets the engine type (s, h, x, y unit concatenation; l, g, p, r, v, F, M, or any neural pack, neural; d dictionary; otherwise HMM), speakers of multi-speaker and multilingual packs use types 4 and 6 (the latter spoken with `speakWithLocales`), and a pack's engine gives way to the bundled one when it cannot run several voices at once or for Korean packs 4.2.24.30 and 4.2.25.17. FP16 is on for neural voices where the device supports it.
- Classes under `com.samsung.SMT`, `SMX`, `SME`, `SMJ` and `SMN` exist so the Samsung libraries can register their JNI methods; their names and signatures must not change. The SM-P600, S4 and J5 engines register on the same class names as the Note 2 one, and the Note 4 one on `com/samsung/SMT/engine/SmtTTS`, the 3.5 engine's class, so the import script rewrites them to `com/samsung/SMX`, `SME`, `SMJ` and `SMN` inside those .so files.
- Device engines: the app runs as a 32-bit process where possible (`use32bitAbi`) and loads them with `System.loadLibrary`; `app/src/main/cpp/{cutils,utils,expat}.c` (ndk-build, armeabi-v7a only) stand in for private platform libraries the Note 2 and SM-P600 engines link against. In a 64-bit process, `EmulatedEngine` hands the engine .so from `assets/engines/` to `libsmtemu.so`. The engines open voice files by path, so they get the folder `/voicefs` (`VoiceFs`, `cpp/voicefs.h`), served from the files' descriptors without copies: by libsmtemu's fopen shim, or for a native engine by libsmtvoicefs, which repoints the engine's fopen GOT entry. Only if that fails are the files copied to `noBackupFilesDir`.
- `app/src/main/cpp/emu/emu.cpp` (libsmtemu): maps the 32-bit ELF into a flat 384 MB arena, applies its relocations, resolves imports to SVC thunks implemented on the host (`shim`; `bsearch`, which calls back into the engine, is ARM code in the arena), fakes JNIEnv and JavaVM, and runs the code with dynarmic. It keeps one engine at a time; `emu_init` resets all state. Define `EMU_MAIN` to build it as a standalone PC harness.
- Voice data is frontend files (`lng`, `cart`, `pb`) plus model files (`am`, `cfg`, `dt`, `qs`). These extensions are `noCompress` so the engines can read them through file descriptors. Neural packs only hand their files to Samsung's own app, so they are read straight from the pack APK (`readFromApk`); what a pack stores deflated (most of an old HD pack) voicefs serves from memory instead.

## Native build notes

- Gradle does not build libsmtemu. After changing `emu.cpp`, rerun `tools/build_emulator.py`; Gradle packages whatever is in `jniLibs`.
- `build_emulator.py` takes CMake and Ninja from PATH (as on CI), or else from the newest Visual Studio with C++ CMake tools for Windows that vswhere finds: here Build Tools 18 (`vcvars64.bat` for MSVC builds). There is no SDK CMake. The NDK is the `ndkVersion` of `app/build.gradle.kts` (30.0.16248370) in the SDK that `ANDROID_HOME` points to.
- CMake 4 dropped FindBoost. `tools/cmake/Boost` is a header-only `BoostConfig` that points at `third_party/boost`.
- dynarmic's bundled fmt needs `-include stdlib.h` under NDK clang; `build_emulator.py` sets it.
- Keep C and C++ sources ASCII. Under the Korean code page, MSVC misread a Korean comment and swallowed the next line; build with `/utf-8`.
- Watch for the most vexing parse: `std::vector<u8> b(size_t(n))` declares a function. Use `static_cast`.

## Testing

- Test on the user's Galaxy Tab (SM-X133), which can run 32-bit code; there is no Android emulator any more. It is 601 dp wide in portrait (one pane) and 1006 dp in landscape (two panes). A normal install runs the app 32-bit (native device engines). `adb install --abi` cannot override `use32bitAbi`, so to try the 64-bit path (device engines through libsmtemu, the arm64 3.5 engine) build once with `abiFilters` set to `arm64-v8a` alone and `multiArch`/`use32bitAbi` removed from the manifest, uninstall, install, and put both files back.
- To try a device engine on the PC, build `emu.cpp` with `EMU_MAIN` (MSVC) and run `emu <engine.so> <voice folder> <text> <out.wav> <lang> <country> <type> <number> <spectrum> <effect>`.
- In Git Bash, set `MSYS_NO_PATHCONV=1` for adb so device paths are not rewritten.
- Do not add self-test receivers or debug hooks to the app; the user checks results themselves.

## Working agreements

- Commit as you go, directly on `main`.
- Add new libraries at their latest stable version.
- Never commit Samsung binaries or voice data. `app/src/main/jniLibs/`, `assets/voices/` and `assets/engines/` stay gitignored and come from `tools/import_samsung_assets.py`.
- Keep comments to a minimum.
- The HD voice and the voices bundled with Samsung TTS 3.5.02 are not embedded in the APK, but installed ones must still appear in the voice list.
