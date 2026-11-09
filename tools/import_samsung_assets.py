"""Copy Samsung's engines and voices into the app module (kept out of Git).

usage: python import_samsung_assets.py <SamsungTTS 3.5 APK> <Note 8 tts/lang_SMT>
                                       <Note 2 system folder> <SM-P600 system folder>
                                       <SHV-E300L system folder> <SM-J500N0 system folder>
                                       <SM-N910L system folder>
"""
import filecmp
import os
import shutil
import sys
import zipfile

APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'app', 'src', 'main')
VOICES = os.path.join(APP, 'assets', 'voices')
ENGINE_LIBS = ('libsmtloader.so', 'libsamsungttse.so')
ABIS = ('arm64-v8a', 'armeabi-v7a')
NOTE8_VOICES = ('en_US_f00', 'ko_KR_f00')
NOTE2_VOICES = tuple(f'{lang}_f01' for lang in (
    'de_DE', 'en_US', 'es_ES', 'fr_FR', 'it_IT', 'ja_JP', 'ko_KR', 'pt_PT', 'ru_RU', 'zh_CN')) + ('ko_KR_m01',)
E300_VOICES = tuple(f'{lang}_f00' for lang in (
    'de_DE', 'en_GB', 'en_US', 'es_ES', 'fr_FR', 'it_IT', 'ja_JP', 'ko_KR', 'pt_BR', 'pt_PT', 'ru_RU', 'zh_CN')) + ('ko_KR_m00',)
# The J5 also ships ko_KR_m00, but it speaks exactly like the Note 4 one.
J500_VOICES = ('en_US_f00', 'ko_KR_f00')
N910_VOICES = tuple(f'{lang}_f00' for lang in (
    'de_DE', 'en_GB', 'en_US', 'es_ES', 'es_MX', 'fr_FR', 'hi_IN', 'it_IT', 'ja_JP', 'ko_KR', 'pt_BR', 'pt_PT', 'ru_RU',
    'zh_CN')) + ('ko_KR_m00',)
FRONTEND = ('lng', 'cart', 'pb')
MODEL = ('am', 'cfg', 'dt', 'qs')
# The later engines register on the classes of the Note 2 one (the Note 4 one on the 3.5 engine's SmtTTS), so each
# moves to its own package (same length, so the binary stays valid): (device, library, package, classes).
NOTE2_CLASSES = (b'com/samsung/SMT/SamsungTTSService', b'com/samsung/SMT/EngineManager')
RENAMED_ENGINES = (
    ('p600', 'libsmtp600.so', b'SMX', NOTE2_CLASSES),
    ('e300', 'libsmte300.so', b'SME', NOTE2_CLASSES),
    ('j500', 'libsmtj500.so', b'SMJ', NOTE2_CLASSES),
    ('n910', 'libsmtn910.so', b'SMN', (b'com/samsung/SMT/engine/SmtTTS',)),
)


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


# Model files identical to those of the same voice folder on an already imported device in shared_with are left out;
# the app reads them from there.
def import_voices(device, lang_smt, voices, shared_with=()):
    for voice in voices:
        lang, variant = voice.rsplit('_', 1)
        dst = os.path.join(VOICES, device, voice)
        os.makedirs(dst)
        frontend, model = voice_files(lang_smt, lang, variant)
        names = frontend + [n for n in model if not any(
            shared(os.path.join(lang_smt, n), os.path.join(VOICES, other, voice, n)) for other in shared_with)]
        for name in names:
            shutil.copyfile(os.path.join(lang_smt, name), os.path.join(dst, name))
        print(f'assets/voices/{device}/{voice} ({len(names)} files)')


def shared(path, other):
    return os.path.exists(other) and filecmp.cmp(path, other, shallow=False)


def import_renamed_engine(system, lib_name, package, classes):
    with open(os.path.join(system, 'lib', 'libsamsungtts.so'), 'rb') as f:
        lib = f.read()
    for old in classes:
        if lib.count(old) != 1:
            sys.exit(f'unexpected engine in {system}: {old.decode()} appears {lib.count(old)} times')
        lib = lib.replace(old, old.replace(b'/SMT/', b'/' + package + b'/'))
    with open(os.path.join(APP, 'jniLibs', 'armeabi-v7a', lib_name), 'wb') as f:
        f.write(lib)
    print(f'jniLibs/armeabi-v7a/{lib_name}')


def lang_smt(system):
    return os.path.join(system, 'tts', 'lang_SMT')


if __name__ == '__main__':
    if len(sys.argv) != 8:
        sys.exit(__doc__)
    apk, note8_lang_smt, note2_system, *systems = sys.argv[1:]
    systems = dict(zip(('p600', 'e300', 'j500', 'n910'), systems))
    import_engine(apk)
    shutil.rmtree(VOICES, ignore_errors=True)
    import_voices('Note8', note8_lang_smt, NOTE8_VOICES)
    import_voices('Note2', lang_smt(note2_system), NOTE2_VOICES)
    import_voices('Note10.1', lang_smt(systems['p600']), NOTE2_VOICES, shared_with=('Note2',))
    import_voices('S4', lang_smt(systems['e300']), E300_VOICES)
    import_voices('J5', lang_smt(systems['j500']), J500_VOICES)
    import_voices('Note4', lang_smt(systems['n910']), N910_VOICES, shared_with=('S4', 'Note8'))
    shutil.copyfile(os.path.join(note2_system, 'lib', 'libsamsungtts.so'),
                    os.path.join(APP, 'jniLibs', 'armeabi-v7a', 'libsmt2014.so'))
    print('jniLibs/armeabi-v7a/libsmt2014.so')
    for device, lib, package, classes in RENAMED_ENGINES:
        import_renamed_engine(systems[device], lib, package, classes)
    # The 32-bit engines also ship as assets so the dynarmic emulator (libsmtemu.so) can load them
    # on 64-bit-only devices. Build it with tools/build_emulator.py.
    engines = os.path.join(APP, 'assets', 'engines')
    os.makedirs(engines, exist_ok=True)
    for lib in ['libsmt2014.so'] + [lib for _, lib, _, _ in RENAMED_ENGINES]:
        shutil.copyfile(os.path.join(APP, 'jniLibs', 'armeabi-v7a', lib), os.path.join(engines, lib))
        print(f'assets/engines/{lib}')
