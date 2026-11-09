"""Build libsmtemu.so (the dynarmic-based engine emulator) for the 64-bit ABIs and drop it
into app/src/main/jniLibs, so Gradle packages it like the other engine binaries.

Needs the dynarmic submodule and Boost:
    git submodule update --init --recursive
    python -I tools/fetch_deps.py

usage: python -I tools/build_emulator.py
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
ABIS = ('arm64-v8a', 'x86_64')  # 64-bit only; 32-bit devices run the engine natively


def cmake_and_ninja():
    tools = shutil.which('cmake'), shutil.which('ninja')
    if all(tools):
        return tools
    vswhere = os.path.expandvars(r'%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe')
    vs = os.path.exists(vswhere) and subprocess.check_output([
        vswhere, '-latest', '-prerelease', '-products', '*',
        '-requires', 'Microsoft.VisualStudio.Component.VC.CMake.Project',
        '-property', 'installationPath', '-utf8'], encoding='utf-8').strip()
    if not vs:
        sys.exit('cmake and ninja not on PATH, and no Visual Studio with C++ CMake tools for Windows')
    cmake = os.path.join(vs, 'Common7', 'IDE', 'CommonExtensions', 'Microsoft', 'CMake')
    return os.path.join(cmake, 'CMake', 'bin', 'cmake.exe'), os.path.join(cmake, 'Ninja', 'ninja.exe')


def build(abi, ndk, cmake, ninja):
    src = os.path.join(ROOT, 'app', 'src', 'main', 'cpp', 'emu')
    out = os.path.join(ROOT, 'build', 'emu', abi)
    subprocess.run([
        cmake, '-S', src, '-B', out, '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={ninja}',
        f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
        f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-24',
        f'-DSMT_ROOT={ROOT}',
        f'-DBoost_DIR={ROOT}/tools/cmake/Boost', f'-DCMAKE_PREFIX_PATH={ROOT}/tools/cmake/Boost',
        '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_FLAGS=-include stdlib.h',
    ], check=True)
    subprocess.run([cmake, '--build', out, '--target', 'smtemu'], check=True)
    dst = os.path.join(ROOT, 'app', 'src', 'main', 'jniLibs', abi)
    os.makedirs(dst, exist_ok=True)
    shutil.copyfile(os.path.join(out, 'libsmtemu.so'), os.path.join(dst, 'libsmtemu.so'))
    print(f'jniLibs/{abi}/libsmtemu.so')


if __name__ == '__main__':
    if not os.path.exists(os.path.join(ROOT, 'third_party', 'boost', 'boost', 'version.hpp')):
        sys.exit('third_party/boost missing; run: python -I tools/fetch_deps.py')
    if not os.environ.get('ANDROID_HOME'):
        sys.exit('ANDROID_HOME not set; point it at the Android SDK')
    with open(os.path.join(ROOT, 'app', 'build.gradle.kts'), encoding='utf-8') as f:
        ndk = os.path.join(os.environ['ANDROID_HOME'], 'ndk', re.search(r'ndkVersion = "(.+)"', f.read())[1])
    cmake, ninja = cmake_and_ninja()
    for abi in ABIS:
        build(abi, ndk, cmake, ninja)
