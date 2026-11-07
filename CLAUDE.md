# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

An Android TTS engine (`com.hwangsihu.smt`) that runs Samsung's older TTS engines and voices (Galaxy Note 2, SM-P600, Note 8, plus installed `com.samsung.SMT.lang_*` packs) on current phones. README.md has the user-facing description. The repository is public and holds no Samsung binaries or voice data; those are imported locally into gitignored folders.

## Commands

```sh
git submodule update --init --recursive
python -I tools/fetch_deps.py        # Boost headers into third_party/boost (gitignored)
python -I tools/import_samsung_assets.py <3.5 APK> <Note 8 lang_SMT> <Note 2 system> <SM-P600 system>
python -I tools/build_emulator.py    # libsmtemu.so for arm64-v8a and x86_64, into jniLibs
./gradlew assembleDebug
./gradlew build                      # also runs lint; lint errors fail the build
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk
```

There are no unit tests. Changes are verified by synthesizing speech on a device (see Testing).

## Architecture

- `SmtTtsService` is the `TextToSpeechService`. `VoiceRepository` lists embedded voices (`assets/voices/<device>/<lang>_<variant>`) and installed packs, found through `com.samsung.SMT.action.CHECK_VOICE_DATA` providers; pack metadata decides HD, neural or standard. Per-language alias voices (`ko-default` and so on) let language-only clients such as TalkBack follow the default chosen in the app.
- `Synthesizer.load` picks the engine:
  - `HdSynthesizer`: HD packs, through `PowerTTS` and the pack's own library.
  - `LegacySynthesizer`: Note 2 and SM-P600 voices, through the 2014 engines.
  - `ClassicSynthesizer`: everything else, through the 3.5 engine (`SmtTTS`: `libsmtloader.so` loads `libsamsungttse.so`), or a pack's own `libsamsungtts.so` when it ships one (UC voices such as s05). FP16 is on for neural voices where the device supports it.
- Classes under `com.samsung.SMT` and `com.samsung.SMX` exist so the Samsung libraries can register their JNI methods; their names and signatures must not change. The SM-P600 engine registers on the same class names as the Note 2 one, so the import script rewrites them to `com/samsung/SMX` inside that .so.
- 2014 engines: the app runs as a 32-bit process where possible (`use32bitAbi`) and loads them with `System.loadLibrary`; `app/src/main/cpp/{cutils,utils,expat}.c` (ndk-build, armeabi-v7a only) stand in for private platform libraries they link against. In a 64-bit process, `EmulatedEngine` hands the engine .so from `assets/engines/` to `libsmtemu.so`.
- `app/src/main/cpp/emu/emu.cpp` (libsmtemu): maps the 32-bit ELF into a flat 384 MB arena, applies its relocations, resolves imports to SVC thunks implemented on the host (`shim`), fakes JNIEnv and JavaVM, and runs the code with dynarmic. It keeps one engine at a time; `emu_init` resets all state. Define `EMU_MAIN` to build it as a standalone PC harness.
- Voice data is frontend files (`lng`, `cart`, `pb`) plus model files (`am`, `cfg`, `dt`, `qs`). These extensions are `noCompress` so the 3.5 engine can read them through file descriptors; the 2014 engines get a copy in `noBackupFilesDir`. Neural packs only hand their files to Samsung's own app, so they are read straight from the pack APK (`readFromApk`).

## Native build notes

- Gradle does not build libsmtemu. After changing `emu.cpp`, rerun `tools/build_emulator.py`; Gradle packages whatever is in `jniLibs`.
- CMake and Ninja come from Visual Studio Build Tools 18 (`vcvars64.bat` for MSVC builds), with NDK 30.0.16248370. There is no SDK CMake.
- CMake 4 dropped FindBoost. `tools/cmake/Boost` is a header-only `BoostConfig` that points at `third_party/boost`.
- dynarmic's bundled fmt needs `-include stdlib.h` under NDK clang; `build_emulator.py` sets it.
- Keep C and C++ sources ASCII. Under the Korean code page, MSVC misread a Korean comment and swallowed the next line; build with `/utf-8`.
- Watch for the most vexing parse: `std::vector<u8> b(size_t(n))` declares a function. Use `static_cast`.

## Testing

- Two targets: the user's Galaxy Tab, which runs 32-bit code (the native 2014 path), and the x86_64 Android emulator `Medium_Phone_API_37.0`, where the app runs 64-bit and the 2014 voices go through libsmtemu. The 3.5 engine has no x86_64 build, so other voices fail on the emulator.
- In Git Bash, set `MSYS_NO_PATHCONV=1` for adb so device paths are not rewritten.
- Do not add self-test receivers or debug hooks to the app; the user checks results themselves.

## Working agreements

- Commit as you go, directly on `main`.
- Never commit Samsung binaries or voice data. `app/src/main/jniLibs/`, `assets/voices/` and `assets/engines/` stay gitignored and come from `tools/import_samsung_assets.py`.
- Keep comments to a minimum.
- The HD voice and the voices bundled with Samsung TTS 3.5.02 are not embedded in the APK, but installed ones must still appear in the voice list.
