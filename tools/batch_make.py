#!/usr/bin/env python3
"""批量：把多个角色目录里的 .pth/.ckpt + 参考音频 转成声音包并量化。

用法:
  python batch_make.py "D:/models/乐奈" "D:/models/仁菜" --out models/voices
  python batch_make.py "D:/models" --recursive --out models/voices --keep-fp32

每个角色目录里需要有:
  - 一个 .pth（SoVITS，取体积最大的）
  - 一个 .ckpt（GPT）
  - 若干 .wav（自动挑 3–10 秒、非 segment_* 的作为参考）
  - 可选 .list（`路径|说话人|语言|文本`），用于给参考音频配原文

依赖: genie-tts、torch、numpy、onnx、onnxruntime、soundfile、pypinyin
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from make_voice import convert, ensure_fp32_external  # noqa: E402
from prepare_character import prepare  # noqa: E402
from quantize import quantize  # noqa: E402


def pick_pair(folder: str):
    files = os.listdir(folder)
    pth = sorted([f for f in files if f.endswith(".pth")],
                 key=lambda x: os.path.getsize(os.path.join(folder, x)), reverse=True)
    ckpt = sorted([f for f in files if f.endswith(".ckpt")],
                  key=lambda x: os.path.getsize(os.path.join(folder, x)), reverse=True)
    if not pth or not ckpt:
        return None, None
    return os.path.join(folder, pth[0]), os.path.join(folder, ckpt[0])


def read_lists(folder: str) -> dict:
    out = {}
    for f in os.listdir(folder):
        if not f.endswith(".list"):
            continue
        try:
            with open(os.path.join(folder, f), encoding="utf-8", errors="ignore") as fh:
                for line in fh:
                    parts = line.rstrip("\n").split("|")
                    if len(parts) >= 4:
                        out[os.path.basename(parts[0])] = parts[3]
        except OSError:
            pass
    return out


def pick_ref(folder: str, texts: dict):
    import soundfile as sf

    cands = []
    for dp, _, fs in os.walk(folder):
        for f in sorted(fs):
            if f.lower().endswith(".wav"):
                cands.append(os.path.join(dp, f))
    cands.sort(key=lambda p: (os.path.basename(p).startswith("segment_"), p))
    best = None
    for p in cands:
        try:
            info = sf.info(p)
        except Exception:  # noqa: BLE001
            continue
        if not (2.5 <= info.duration <= 10.0):
            continue
        text = texts.get(os.path.basename(p), "")
        if text:
            return p, text
        if best is None:
            best = (p, "")
    return best if best else (None, "")


def ascii_name(name: str) -> str:
    try:
        from pypinyin import lazy_pinyin
        return "".join(lazy_pinyin(name)).lower().replace(" ", "_")
    except ImportError:
        return "".join(ch for ch in name if ch.isascii() and ch.isalnum()) or "voice"


def process(folder: str, out_root: str, display: str, keep_fp32: bool) -> bool:
    pth, ckpt = pick_pair(folder)
    if not pth:
        print(f"  skip: 缺少 .pth/.ckpt")
        return False
    texts = read_lists(folder)
    wav, text = pick_ref(folder, texts)
    if not wav:
        print("  skip: 没有 2.5–10 秒的可用参考音频")
        return False
    print(f"  pth={os.path.basename(pth)}  ckpt={os.path.basename(ckpt)}")
    print(f"  ref={os.path.basename(wav)}  text={text[:40] or '(空)'}")

    name = ascii_name(display)
    fp32_dir = os.path.join(out_root, f"{name}_fp32")
    final_dir = os.path.join(out_root, name)
    onnx_dir = os.path.join(out_root, f".{name}_onnx")
    try:
        convert(pth, ckpt, onnx_dir)
        prepare(name, onnx_dir, wav, text or "（参考音频）", "zh", fp32_dir, display)
        ensure_fp32_external(fp32_dir)
        quantize(fp32_dir, final_dir)
        if not keep_fp32:
            shutil.rmtree(fp32_dir, ignore_errors=True)
        print(f"  done -> {final_dir}")
        return True
    finally:
        shutil.rmtree(onnx_dir, ignore_errors=True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="批量生成声音包")
    ap.add_argument("dirs", nargs="+", help="角色目录（或父目录，配合 --recursive）")
    ap.add_argument("--out", default="models/voices", help="输出根目录")
    ap.add_argument("--recursive", action="store_true", help="递归查找角色目录")
    ap.add_argument("--keep-fp32", action="store_true", help="保留 fp32 中间产物")
    ap.add_argument("--genie-data", default=None, help="GenieData 目录")
    args = ap.parse_args(argv)

    if args.genie_data:
        os.environ["GENIE_DATA_DIR"] = args.genie_data
    os.makedirs(args.out, exist_ok=True)

    targets = []
    for d in args.dirs:
        if args.recursive:
            for dp, _, fs in os.walk(d):
                if any(f.endswith((".pth", ".ckpt")) for f in fs):
                    targets.append(dp)
        else:
            targets.append(d.rstrip("\\/"))

    ok = 0
    for folder in targets:
        display = os.path.basename(folder)
        print(f"=== {display}")
        try:
            ok += 1 if process(folder, args.out, display, args.keep_fp32) else 0
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001
            print(f"  failed: {e}")

    print(f"DONE {ok}/{len(targets)}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
