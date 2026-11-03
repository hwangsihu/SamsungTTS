"""Copy Samsung's engine libraries and the Note 8 voices into the app module (kept out of Git).

usage: python import_samsung_assets.py <SamsungTTS 3.5 APK> <Note 8 tts/lang_SMT folder>
"""
import os
import shutil
import sys
import zipfile

APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'app', 'src', 'main')
ENGINE_LIBS = ('libsmtloader.so', 'libsamsungttse.so')
ABIS = ('arm64-v8a', 'armeabi-v7a')
NOTE8_VOICES = {
    'ko_KR_f00': ['smt_ko_KR.lng'] + [f'smt_ko_KR_f00.{e}' for e in ('am', 'cfg', 'dt', 'qs')],
    'en_US_f00': ['smt_en_US.lng'] + [f'smt_en_US_f00.{e}' for e in ('am', 'cfg', 'dt', 'qs')],
}


def import_engine(apk):
    with zipfile.ZipFile(apk) as z:
        for abi in ABIS:
            dst = os.path.join(APP, 'jniLibs', abi)
            os.makedirs(dst, exist_ok=True)
            for lib in ENGINE_LIBS:
                with z.open(f'lib/{abi}/{lib}') as src, open(os.path.join(dst, lib), 'wb') as out:
                    shutil.copyfileobj(src, out)
                print(f'jniLibs/{abi}/{lib}')


def import_voices(lang_smt):
    for voice, files in NOTE8_VOICES.items():
        dst = os.path.join(APP, 'assets', 'voices', voice)
        os.makedirs(dst, exist_ok=True)
        for name in files:
            shutil.copyfile(os.path.join(lang_smt, name), os.path.join(dst, name))
        print(f'assets/voices/{voice} ({len(files)} files)')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    import_engine(sys.argv[1])
    import_voices(sys.argv[2])
