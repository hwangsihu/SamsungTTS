"""Copy Samsung's engines and voices into the app module (kept out of Git).

usage: python -I tools/import_samsung_assets.py <folder>

The folder, subfolders included, holds the Samsung TTS 3.5.02.16 APK and the firmware of the SM-N950N, SHV-E250L,
SM-P600, SHV-E300L, SM-J500N0 and SM-N910L: SamFW zips, their unpacked folders or just their AP files. The engines
and voices are read straight from the system images in the AP files.
"""
import bisect
import collections
import os
import shutil
import struct
import sys
import tarfile
import tempfile
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
# moves to its own package (same length, so the binary stays valid).
NOTE2_CLASSES = (b'com/samsung/SMT/SamsungTTSService', b'com/samsung/SMT/EngineManager')
# build: how the build in its AP file's name starts. shared_with: voice folders whose identical model files are left
# out, so each device comes after them.
Device = collections.namedtuple('Device', 'folder model build voices shared_with engine package classes')
DEVICES = (
    Device('Note8', 'SM-N950N', 'N950N', NOTE8_VOICES, (), None, None, ()),
    Device('Note2', 'SHV-E250L', 'E250L', NOTE2_VOICES, (), 'libsmt2014.so', None, ()),
    Device('Note10.1', 'SM-P600', 'P600', NOTE2_VOICES, ('Note2',), 'libsmtp600.so', b'SMX', NOTE2_CLASSES),
    Device('S4', 'SHV-E300L', 'E300L', E300_VOICES, (), 'libsmte300.so', b'SME', NOTE2_CLASSES),
    Device('J5', 'SM-J500N0', 'J500N0', J500_VOICES, (), 'libsmtj500.so', b'SMJ', NOTE2_CLASSES),
    Device('Note4', 'SM-N910L', 'N910L', N910_VOICES, ('S4', 'Note8'), 'libsmtn910.so', b'SMN',
           (b'com/samsung/SMT/engine/SmtTTS',)),
)
IMAGES = ('system.img', 'system.img.ext4', 'system.img.lz4')
SPARSE_RAW, SPARSE_FILL, SPARSE_DONT_CARE, SPARSE_CRC32 = 0xCAC1, 0xCAC2, 0xCAC3, 0xCAC4


class Part:
    def __init__(self, f, offset, size):
        self.f, self.offset, self.size = f, offset, size

    def read(self, pos, size):
        self.f.seek(self.offset + pos)
        return self.f.read(max(0, min(size, self.size - pos)))


def lz4_block(src):
    dst, i, end = bytearray(), 0, len(src)
    while True:
        token = src[i]
        i += 1
        n = token >> 4
        if n == 15:
            while src[i] == 255:
                n += 255
                i += 1
            n += src[i]
            i += 1
        dst += src[i:i + n]
        i += n
        if i >= end:
            return bytes(dst)
        offset = src[i] | src[i + 1] << 8
        i += 2
        n = token & 15
        if n == 15:
            while src[i] == 255:
                n += 255
                i += 1
            n += src[i]
            i += 1
        n += 4
        start = len(dst) - offset
        if offset >= n:
            dst += dst[start:start + n]
        else:
            dst += (dst[start:] * (n // offset + 1))[:n]


class Lz4:
    """An LZ4 frame of independent blocks, each decompressed when it is read."""

    def __init__(self, src):
        magic, flags, bd = struct.unpack('<IBB', src.read(0, 6))
        if magic != 0x184D2204 or not flags & 0x20:
            raise ValueError('not an LZ4 frame of independent blocks')
        self.src, self.block_size, self.blocks, self.cache = src, 1 << (2 * (bd >> 4 & 7) + 8), [], {}
        pos = 7 + (8 if flags & 8 else 0) + (4 if flags & 1 else 0)
        while size := struct.unpack('<I', src.read(pos, 4))[0]:
            self.blocks.append((pos + 4, size & 0x7FFFFFFF, size >> 31))
            pos += 4 + (size & 0x7FFFFFFF) + (4 if flags & 0x10 else 0)

    def block(self, i):
        if i not in self.cache:
            pos, size, stored = self.blocks[i]
            data = self.src.read(pos, size)
            if not stored:
                data = lz4_block(data)
            if len(data) != self.block_size and i != len(self.blocks) - 1:
                raise ValueError('LZ4 block of unexpected size')
            if len(self.cache) == 16:
                del self.cache[next(iter(self.cache))]
            self.cache[i] = data
        return self.cache[i]

    def read(self, pos, size):
        out = bytearray()
        while len(out) < size and pos // self.block_size < len(self.blocks):
            i, skip = divmod(pos, self.block_size)
            data = self.block(i)[skip:skip + size - len(out)]
            if not data:
                break
            out += data
            pos += len(data)
        return bytes(out)


class Sparse:
    """An Android sparse image, read as the image it stands for."""

    def __init__(self, src):
        _, _, _, header, chunk_header, block, blocks, chunks = struct.unpack('<IHHHHIII', src.read(0, 24))
        self.src, self.size, self.starts, self.chunks = src, block * blocks, [], []
        pos, out = header, 0
        for _ in range(chunks):
            kind, _, count, total = struct.unpack('<HHII', src.read(pos, 12))
            if kind not in (SPARSE_RAW, SPARSE_FILL, SPARSE_DONT_CARE, SPARSE_CRC32):
                raise ValueError(f'unknown sparse chunk {kind:#x}')
            if count:
                data = pos + chunk_header
                self.starts.append(out)
                self.chunks.append((count * block, kind, src.read(data, 4) if kind == SPARSE_FILL else data))
                out += count * block
            pos += total

    def read(self, pos, size):
        out = bytearray()
        size = min(size, self.size - pos)
        while size > 0:
            i = bisect.bisect_right(self.starts, pos) - 1
            length, kind, data = self.chunks[i]
            skip = pos - self.starts[i]
            n = min(size, length - skip)
            if kind == SPARSE_RAW:
                out += self.src.read(data + skip, n)
            elif kind == SPARSE_FILL:
                out += (data * (n // 4 + 2))[skip % 4:skip % 4 + n]
            else:
                out += bytes(n)
            pos += n
            size -= n
        return bytes(out)


class Ext4:
    def __init__(self, image):
        self.image = image
        sb = image.read(1024, 1024)
        if struct.unpack_from('<H', sb, 0x38)[0] != 0xEF53:
            raise ValueError('not an ext4 image')
        first, log = struct.unpack_from('<II', sb, 0x14)
        self.per_group, = struct.unpack_from('<I', sb, 0x28)
        self.inode_size = struct.unpack_from('<H', sb, 0x58)[0] if struct.unpack_from('<I', sb, 0x4C)[0] else 128
        self.desc_size = struct.unpack_from('<H', sb, 0xFE)[0] if struct.unpack_from('<I', sb, 0x60)[0] & 0x80 else 32
        self.block = 1024 << log
        self.descs = (first + 1) * self.block

    def inode(self, ino):
        group, index = divmod(ino - 1, self.per_group)
        desc = self.image.read(self.descs + group * self.desc_size, self.desc_size)
        table = struct.unpack_from('<I', desc, 8)[0]
        if self.desc_size >= 64:
            table |= struct.unpack_from('<I', desc, 0x28)[0] << 32
        return self.image.read(table * self.block + index * self.inode_size, self.inode_size)

    def extents(self, node):
        magic, entries, _, depth = struct.unpack_from('<HHHH', node)
        if magic != 0xF30A:
            raise ValueError('file without extents')
        for k in range(entries):
            if depth:
                _, lo, hi = struct.unpack_from('<IIH', node, 12 + 12 * k)
                yield from self.extents(self.image.read((hi << 32 | lo) * self.block, self.block))
            else:
                logical, count, hi, lo = struct.unpack_from('<IHHI', node, 12 + 12 * k)
                yield logical, hi << 32 | lo, count - 32768 if count > 32768 else count, count > 32768

    def read(self, ino):
        inode = self.inode(ino)
        size = struct.unpack_from('<I', inode, 4)[0] | struct.unpack_from('<I', inode, 0x6C)[0] << 32
        data = bytearray()
        for logical, start, count, unwritten in self.extents(inode[0x28:0x64]):
            data += bytes(max(0, logical * self.block - len(data)))
            data += bytes(count * self.block) if unwritten else self.image.read(start * self.block, count * self.block)
        return bytes(data[:size]) + bytes(max(0, size - len(data)))

    def listdir(self, ino):
        data, entries, pos = self.read(ino), {}, 0
        while pos + 8 <= len(data):
            child, length, name_length = struct.unpack_from('<IHB', data, pos)
            if length < 8:
                break
            if child:
                entries[data[pos + 8:pos + 8 + name_length].decode('utf-8', 'replace')] = child
            pos += length
        return entries

    def lookup(self, path):
        ino = 2
        for name in path.split('/'):
            ino = self.listdir(ino).get(name)
            if ino is None:
                raise FileNotFoundError(path)
        return ino


class System:
    """The files in a firmware's system image, read in place from an AP file or from a copy out of a zip."""

    def __init__(self, model, source, work):
        path, member = source
        self.model, self.copy = model, None
        if member is None:
            self.file = open(path, 'rb')
            with tarfile.open(fileobj=self.file, mode='r:') as tar:
                image = next((m for m in tar if m.name in IMAGES), None)
            if image is None:
                sys.exit(f'{model}: no system image in {path}')
            src = Part(self.file, image.offset_data, image.size)
        else:
            print(f'{model}: copying the system image out of {path}')
            with zipfile.ZipFile(path) as z, z.open(member) as ap, tarfile.open(fileobj=ap, mode='r|') as tar:
                image = next((m for m in tar if m.name in IMAGES), None)
                if image is None:
                    sys.exit(f'{model}: no system image in {member} in {path}')
                self.copy = os.path.join(work, image.name)
                with tar.extractfile(image) as f, open(self.copy, 'wb') as out:
                    shutil.copyfileobj(f, out, 1 << 24)
            self.file = open(self.copy, 'rb')
            src = Part(self.file, 0, image.size)
        if image.name.endswith('.lz4'):
            src = Lz4(src)
        if src.read(0, 4) == b'\x3a\xff\x26\xed':
            src = Sparse(src)
        self.fs = Ext4(src)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.file.close()
        if self.copy:
            os.remove(self.copy)

    def listdir(self, path):
        try:
            return self.fs.listdir(self.fs.lookup(path))
        except FileNotFoundError:
            sys.exit(f'{self.model}: no {path} in the system image')

    def read(self, path):
        try:
            return self.fs.read(self.fs.lookup(path))
        except FileNotFoundError:
            sys.exit(f'{self.model}: no {path} in the system image')


def find_inputs(folder):
    apks, aps = [], {}
    for root, _, names in os.walk(folder):
        for name in sorted(names):
            path = os.path.join(root, name)
            if name.lower().endswith('.apk'):
                apks.append(path)
            elif name.lower().endswith('.zip'):
                try:
                    with zipfile.ZipFile(path) as z:
                        members = z.namelist()
                except zipfile.BadZipFile:
                    continue
                for member in members:
                    add_ap(aps, os.path.basename(member), (path, member))
            else:
                add_ap(aps, name, (path, None))
    firmware = {}
    for device in DEVICES:
        found = aps.get(device.folder, {})
        if len(found) > 1:
            sys.exit(f'more than one firmware for the {device.model}: {", ".join(sorted(found))}')
        if found:
            sources = next(iter(found.values()))
            firmware[device.folder] = next((s for s in sources if s[1] is None), sources[0])
    missing = [device.model for device in DEVICES if device.folder not in firmware]
    if missing:
        sys.exit(f'no firmware for the {", ".join(missing)} in {folder}')
    return engine_apk(folder, apks), firmware


def add_ap(aps, name, source):
    if name.startswith('AP_') and name.endswith(('.tar', '.tar.md5')):
        for device in DEVICES:
            if name[3:].startswith(device.build):
                aps.setdefault(device.folder, {}).setdefault(name, []).append(source)


def engine_apk(folder, apks):
    found = []
    for path in apks:
        try:
            with zipfile.ZipFile(path) as z:
                names = set(z.namelist())
        except zipfile.BadZipFile:
            continue
        if all(f'lib/{abi}/{lib}' in names for abi in ABIS for lib in ENGINE_LIBS):
            found.append(path)
    if not found:
        sys.exit(f'no Samsung TTS 3.5 APK with arm64-v8a and armeabi-v7a libraries in {folder}')
    if len(found) > 1:
        sys.exit(f'more than one Samsung TTS 3.5 APK: {", ".join(found)}')
    return found[0]


def import_engine(apk):
    with zipfile.ZipFile(apk) as z:
        for abi in ABIS:
            dst = os.path.join(APP, 'jniLibs', abi)
            os.makedirs(dst, exist_ok=True)
            for lib in ENGINE_LIBS:
                with z.open(f'lib/{abi}/{lib}') as src, open(os.path.join(dst, lib), 'wb') as out:
                    shutil.copyfileobj(src, out)
                print(f'jniLibs/{abi}/{lib}')


# Model files identical to those of the same voice folder on an already imported device in shared_with are left out;
# the app reads them from there.
def import_voices(system, device, voices, shared_with):
    lang_smt = system.listdir('tts/lang_SMT')
    for voice in voices:
        lang = voice.rsplit('_', 1)[0]
        dst = os.path.join(VOICES, device, voice)
        os.makedirs(dst)
        model = [f'smt_{voice}.{e}' for e in MODEL]
        names = [f'smt_{lang}.{e}' for e in FRONTEND if f'smt_{lang}.{e}' in lang_smt] + model
        copied = 0
        for name in names:
            if name not in lang_smt:
                sys.exit(f'{system.model}: no tts/lang_SMT/{name} in the system image')
            data = system.fs.read(lang_smt[name])
            if name in model and any(same(data, os.path.join(VOICES, other, voice, name)) for other in shared_with):
                continue
            with open(os.path.join(dst, name), 'wb') as f:
                f.write(data)
            copied += 1
        print(f'assets/voices/{device}/{voice} ({copied} files)')


def same(data, path):
    if not os.path.exists(path):
        return False
    with open(path, 'rb') as f:
        return f.read() == data


def import_device_engine(system, lib_name, package, classes):
    lib = system.read('lib/libsamsungtts.so')
    for old in classes:
        if lib.count(old) != 1:
            sys.exit(f'unexpected engine in the {system.model}: {old.decode()} appears {lib.count(old)} times')
        lib = lib.replace(old, old.replace(b'/SMT/', b'/' + package + b'/'))
    with open(os.path.join(APP, 'jniLibs', 'armeabi-v7a', lib_name), 'wb') as f:
        f.write(lib)
    print(f'jniLibs/armeabi-v7a/{lib_name}')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk, firmware = find_inputs(sys.argv[1])
    import_engine(apk)
    shutil.rmtree(VOICES, ignore_errors=True)
    with tempfile.TemporaryDirectory() as work:
        for device in DEVICES:
            path, member = firmware[device.folder]
            print(f'{device.model}: {os.path.basename(member or path)}')
            with System(device.model, firmware[device.folder], work) as system:
                import_voices(system, device.folder, device.voices, device.shared_with)
                if device.engine:
                    import_device_engine(system, device.engine, device.package, device.classes)
    # The 32-bit engines also ship as assets so the dynarmic emulator (libsmtemu.so) can load them
    # on 64-bit-only devices. Build it with tools/build_emulator.py.
    engines = os.path.join(APP, 'assets', 'engines')
    os.makedirs(engines, exist_ok=True)
    for lib in [device.engine for device in DEVICES if device.engine]:
        shutil.copyfile(os.path.join(APP, 'jniLibs', 'armeabi-v7a', lib), os.path.join(engines, lib))
        print(f'assets/engines/{lib}')
