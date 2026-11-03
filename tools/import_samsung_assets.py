"""Copy Samsung's engines and voices into the app module (kept out of Git).

usage: python import_samsung_assets.py <SamsungTTS 3.5 APK> <Note 8 tts/lang_SMT> <Note 2 system folder>
"""
import os
import shutil
import sys
import zipfile

APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'app', 'src', 'main')
ENGINE_LIBS = ('libsmtloader.so', 'libsamsungttse.so')
ABIS = ('arm64-v8a', 'armeabi-v7a')
NOTE8_VOICES = {'ko_KR': 'f00', 'en_US': 'f00'}
NOTE2_VOICES = {lang: 'f01' for lang in (
    'de_DE', 'en_US', 'es_ES', 'fr_FR', 'it_IT', 'ja_JP', 'ko_KR', 'pt_PT', 'ru_RU', 'zh_CN')}
FRONTEND_EXTRAS = ('cart', 'pb')


def import_engine(apk):
    with zipfile.ZipFile(apk) as z:
        for abi in ABIS:
            dst = os.path.join(APP, 'jniLibs', abi)
            os.makedirs(dst, exist_ok=True)
            for lib in ENGINE_LIBS:
                with z.open(f'lib/{abi}/{lib}') as src, open(os.path.join(dst, lib), 'wb') as out:
                    shutil.copyfileobj(src, out)
                print(f'jniLibs/{abi}/{lib}')


def import_voices(device, lang_smt, voices):
    for lang, variant in voices.items():
        dst = os.path.join(APP, 'assets', 'voices', device, f'{lang}_{variant}')
        shutil.rmtree(dst, ignore_errors=True)
        os.makedirs(dst)
        frontend = [f'smt_{lang}.{e}' for e in ('lng',) + FRONTEND_EXTRAS]
        names = [n for n in frontend if os.path.exists(os.path.join(lang_smt, n))]
        names += [f'smt_{lang}_{variant}.{e}' for e in ('am', 'cfg', 'dt', 'qs')]
        for name in names:
            shutil.copyfile(os.path.join(lang_smt, name), os.path.join(dst, name))
        print(f'assets/voices/{device}/{lang}_{variant} ({len(names)} files)')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    apk, note8_lang_smt, note2_system = sys.argv[1:]
    import_engine(apk)
    shutil.rmtree(os.path.join(APP, 'assets', 'voices'), ignore_errors=True)
    import_voices('note8', note8_lang_smt, NOTE8_VOICES)
    import_voices('note2', os.path.join(note2_system, 'tts', 'lang_SMT'), NOTE2_VOICES)
    shutil.copyfile(os.path.join(note2_system, 'lib', 'libsamsungtts.so'),
                    os.path.join(APP, 'jniLibs', 'armeabi-v7a', 'libsmt2014.so'))
    print('jniLibs/armeabi-v7a/libsmt2014.so')
