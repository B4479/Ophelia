#!/usr/bin/env python3
"""把声音包目录打成 App 可导入的 zip，并生成 manifest.json（逐文件 SHA-256）。

用法:
  python pack_zip.py models/voices/roxy --out dist/洛琪希.zip
  python pack_zip.py models/voices/roxy            # 默认输出 ./<目录名>.zip

说明:
  - 默认不压缩（ZIP_STORED）：onnx/bin 本身已压缩，存储模式更快、导入更省 CPU
  - --deflate 可改用 DEFLATE（体积约降 3–8%，打包更慢）
  - zip 内保留顶层目录，App 在任意层级找到 meta.properties 即可
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import zipfile


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pack(src: str, out: str, deflate: bool = False) -> dict:
    src = os.path.abspath(src)
    if not os.path.isdir(src):
        raise SystemExit(f"不是目录: {src}")
    if not os.path.isfile(os.path.join(src, "meta.properties")):
        raise SystemExit(f"{src} 里没有 meta.properties，先运行 make_voice.py / prepare_character.py")

    top = os.path.basename(src)
    method = zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED
    entries = []
    with zipfile.ZipFile(out, "w", method, allowZip64=True) as z:
        for dp, _, fs in os.walk(src):
            for f in sorted(fs):
                p = os.path.join(dp, f)
                rel = os.path.relpath(p, src).replace("\\", "/")
                arc = f"{top}/{rel}"
                z.write(p, arc)
                entries.append({
                    "path": rel,
                    "size": os.path.getsize(p),
                    "sha256": sha256_file(p),
                })

    manifest = {
        "pack": top,
        "zip": os.path.basename(out),
        "zip_size": os.path.getsize(out),
        "zip_sha256": sha256_file(out),
        "compression": "deflate" if deflate else "store",
        "files": entries,
    }
    mpath = os.path.splitext(out)[0] + ".manifest.json"
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="声音包 → zip + manifest")
    ap.add_argument("src", help="声音包目录")
    ap.add_argument("--out", default=None, help="输出 zip 路径")
    ap.add_argument("--deflate", action="store_true", help="使用 DEFLATE 压缩")
    args = ap.parse_args(argv)

    out = args.out or (os.path.basename(os.path.abspath(args.src)) + ".zip")
    m = pack(args.src, out, args.deflate)
    print(f"ZIP {out} {m['zip_size'] / 1048576:.1f} MB  sha256={m['zip_sha256'][:16]}…")
    print(f"MANIFEST {os.path.splitext(out)[0]}.manifest.json  files={len(m['files'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
