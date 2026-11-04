"""Download the build dependencies that are not git submodules (kept out of Git).

Only Boost: dynarmic needs its header-only ICL and Variant libraries and has no bundled
fallback for them. Everything else dynarmic uses comes in as its own submodules.

usage: python -I tools/fetch_deps.py
"""
import hashlib
import os
import sys
import tarfile
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir)
DEPS = os.path.join(ROOT, 'third_party')

BOOST_VERSION = '1.92.0'
BOOST_ARCHIVE = f'boost-{BOOST_VERSION}-b2-nodocs.tar.xz'
BOOST_URL = f'https://github.com/boostorg/boost/releases/download/boost-{BOOST_VERSION}/{BOOST_ARCHIVE}'
BOOST_SHA256 = 'ea7b982002cc9dfbe59b0b217b206f470dc75f3de0bb2973d844118934d82411'


def fetch(url, sha256):
    cache = os.path.join(DEPS, os.path.basename(url))
    if not (os.path.exists(cache) and digest(cache) == sha256):
        print(f'downloading {url}')
        urllib.request.urlretrieve(url, cache)
        if digest(cache) != sha256:
            sys.exit(f'{os.path.basename(url)}: sha256 mismatch (got {digest(cache)})')
    return cache


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


if __name__ == '__main__':
    os.makedirs(DEPS, exist_ok=True)
    archive = fetch(BOOST_URL, BOOST_SHA256)
    target = os.path.join(DEPS, 'boost')
    if not os.path.isdir(target):
        print(f'extracting {os.path.basename(archive)}')
        with tarfile.open(archive) as tar:
            tar.extractall(DEPS, filter='data')
        os.rename(os.path.join(DEPS, f'boost-{BOOST_VERSION}'), target)
    print(f'boost {BOOST_VERSION} ready at third_party/boost')
