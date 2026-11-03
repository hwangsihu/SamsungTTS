"""Copy Samsung's engines and voices into the app module (kept out of Git).

usage: python import_samsung_assets.py <SamsungTTS 3.5 APK> <Note 8 tts/lang_SMT>
                                       <Note 2 system folder> <SM-P600 system folder>
"""
import filecmp
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
FRONTEND = ('lng', 'cart', 'pb')
MODEL = ('am', 'cfg', 'dt', 'qs')
# The SM-P600 engine registers on the same classes as the Note 2 one; move it to com/samsung/SMX.
P600_CLASSES = ((b'com/samsung/SMT/SamsungTTSService', b'com/samsung/SMX/SamsungTTSService'),
                (b'com/samsung/SMT/EngineManager', b'com/samsung/SMX/EngineManager'))


def import_engine(apk):
    with zipfile.ZipFile(apk) as z:
        for abi in ABIS:
            dst = os.path.join(APP, 'jniLibs', abi)
            os.makedirs(dst, exist_ok=True)
            for lib in ENGINE_LIBS:
                with z.open(f'lib/{abi}/{lib}') as src, open(os.path.join(dst, lib), 'wb') as out:
                    shutil.copyfileobj(src, out)
                print(f'jniLibs/{abi}/{lib}')


def voice_files(lang_smt, lang, variant):
    frontend = [f'smt_{lang}.{e}' for e in FRONTEND if os.path.exists(os.path.join(lang_smt, f'smt_{lang}.{e}'))]
    return frontend, [f'smt_{lang}_{variant}.{e}' for e in MODEL]


def import_voices(device, lang_smt, voices, shared_with=None):
    for lang, variant in voices.items():
        dst = os.path.join(APP, 'assets', 'voices', device, f'{lang}_{variant}')
        os.makedirs(dst)
        frontend, model = voice_files(lang_smt, lang, variant)
        names = frontend + [n for n in model if shared_with is None
                            or not filecmp.cmp(os.path.join(lang_smt, n), os.path.join(shared_with, n), shallow=False)]
        for name in names:
            shutil.copyfile(os.path.join(lang_smt, name), os.path.join(dst, name))
        print(f'assets/voices/{device}/{lang}_{variant} ({len(names)} files)')


def import_p600_engine(p600_system):
    with open(os.path.join(p600_system, 'lib', 'libsamsungtts.so'), 'rb') as f:
        lib = f.read()
    for old, new in P600_CLASSES:
        if lib.count(old) != 1:
            sys.exit(f'unexpected SM-P600 engine: {old.decode()} appears {lib.count(old)} times')
        lib = lib.replace(old, new)
    with open(os.path.join(APP, 'jniLibs', 'armeabi-v7a', 'libsmtp600.so'), 'wb') as f:
        f.write(lib)
    print('jniLibs/armeabi-v7a/libsmtp600.so')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    apk, note8_lang_smt, note2_system, p600_system = sys.argv[1:]
    note2_lang_smt = os.path.join(note2_system, 'tts', 'lang_SMT')
    import_engine(apk)
    shutil.rmtree(os.path.join(APP, 'assets', 'voices'), ignore_errors=True)
    import_voices('note8', note8_lang_smt, NOTE8_VOICES)
    import_voices('note2', note2_lang_smt, NOTE2_VOICES)
    import_voices('p600', os.path.join(p600_system, 'tts', 'lang_SMT'), NOTE2_VOICES, shared_with=note2_lang_smt)
    shutil.copyfile(os.path.join(note2_system, 'lib', 'libsamsungtts.so'),
                    os.path.join(APP, 'jniLibs', 'armeabi-v7a', 'libsmt2014.so'))
    print('jniLibs/armeabi-v7a/libsmt2014.so')
    import_p600_engine(p600_system)
