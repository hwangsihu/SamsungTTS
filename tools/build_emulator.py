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
SDK = os.environ.get('ANDROID_HOME') or os.path.join(os.path.expanduser('~'), 'AppData', 'Local', 'Android', 'Sdk')
with open(os.path.join(ROOT, 'app', 'build.gradle.kts'), encoding='utf-8') as f:
    NDK = os.path.join(SDK, 'ndk', re.search(r'ndkVersion = "(.+)"', f.read())[1])
VS = r'C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake'
CMAKE = os.path.join(VS, 'CMake', 'bin', 'cmake.exe')
NINJA = os.path.join(VS, 'Ninja', 'ninja.exe')
if not os.path.exists(CMAKE):
    CMAKE, NINJA = shutil.which('cmake'), shutil.which('ninja')
ABIS = ('arm64-v8a', 'x86_64')  # 64-bit only; 32-bit devices run the engine natively


def build(abi):
    src = os.path.join(ROOT, 'app', 'src', 'main', 'cpp', 'emu')
    out = os.path.join(ROOT, 'build', 'emu', abi)
    subprocess.run([
        CMAKE, '-S', src, '-B', out, '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={NINJA}',
        f'-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake',
        f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-24',
        f'-DSMT_ROOT={ROOT}',
        f'-DBoost_DIR={ROOT}/tools/cmake/Boost', f'-DCMAKE_PREFIX_PATH={ROOT}/tools/cmake/Boost',
        '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_FLAGS=-include stdlib.h',
    ], check=True)
    subprocess.run([CMAKE, '--build', out, '--target', 'smtemu'], check=True)
    dst = os.path.join(ROOT, 'app', 'src', 'main', 'jniLibs', abi)
    os.makedirs(dst, exist_ok=True)
    shutil.copyfile(os.path.join(out, 'libsmtemu.so'), os.path.join(dst, 'libsmtemu.so'))
    print(f'jniLibs/{abi}/libsmtemu.so')


if __name__ == '__main__':
    if not os.path.exists(os.path.join(ROOT, 'third_party', 'boost', 'boost', 'version.hpp')):
        sys.exit('third_party/boost missing; run: python -I tools/fetch_deps.py')
    for abi in ABIS:
        build(abi)
