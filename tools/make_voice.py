#!/usr/bin/env python3
"""GPT-SoVITS 权重（.pth + .ckpt）→ Genie ONNX → App 声音包，一步到位。

用法:
  python make_voice.py --pth model.pth --ckpt model.ckpt \
      --ref-wav ref.wav --ref-text "参考句原文" --ref-lang ja \
      --name roxy --out models/voices/roxy_fp32

可选:
  --genie-data <dir>   GenieData 目录（也可用环境变量 GENIE_DATA_DIR）
  --display-name       App 里显示的名字（默认 --name）
  --keep-onnx <dir>    保留中间 ONNX 目录（默认临时目录，结束即删）

依赖: genie-tts、torch、numpy（见 requirements.txt）
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from prepare_character import prepare  # noqa: E402


def convert(pth: str, ckpt: str, onnx_dir: str) -> None:
    try:
        import genie_tts as genie
    except ImportError:
        raise SystemExit("缺少 genie-tts，请先 pip install -r requirements.txt")
    os.makedirs(onnx_dir, exist_ok=True)
    print(f"[1/3] 转换 {os.path.basename(pth)} + {os.path.basename(ckpt)} → {onnx_dir}")
    genie.convert_to_onnx(torch_pth_path=pth, torch_ckpt_path=ckpt, output_dir=onnx_dir)


def ensure_fp32_external(voice_dir: str) -> None:
    """把 fp16 外置权重转成 fp32（端侧引擎只读 fp32）。"""
    try:
        import numpy as np
    except ImportError:
        raise SystemExit("缺少 numpy，请先 pip install -r requirements.txt")
    pairs = [("t2s_shared_fp16.bin", "t2s_shared_fp32.bin"), ("vits_fp16.bin", "vits_fp32.bin")]
    for src, dst in pairs:
        s = os.path.join(voice_dir, src)
        t = os.path.join(voice_dir, dst)
        if not os.path.exists(s):
            continue
        if os.path.exists(t) and os.path.getsize(t) > 0:
            continue
        arr = np.fromfile(s, dtype=np.float16).astype(np.float32)
        arr.tofile(t)
        print(f"      {dst} {os.path.getsize(t) // 1048576} MB")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="GPT-SoVITS → Genie ONNX → 声音包")
    ap.add_argument("--pth", required=True, help="SoVITS 权重 .pth")
    ap.add_argument("--ckpt", required=True, help="GPT 权重 .ckpt")
    ap.add_argument("--ref-wav", required=True, help="参考音频 wav（3–10 秒）")
    ap.add_argument("--ref-text", required=True, help="参考音频对应原文")
    ap.add_argument("--ref-lang", default="zh", help="参考音频语言（默认 zh）")
    ap.add_argument("--name", required=True, help="包内标识（ASCII）")
    ap.add_argument("--out", required=True, help="输出声音包目录")
    ap.add_argument("--display-name", default=None, help="App 内显示名")
    ap.add_argument("--genie-data", default=None, help="GenieData 目录")
    ap.add_argument("--keep-onnx", default=None, help="保留中间 ONNX 的目录")
    args = ap.parse_args(argv)

    if args.genie_data:
        os.environ["GENIE_DATA_DIR"] = args.genie_data

    tmp = None
    onnx_dir = args.keep_onnx
    if not onnx_dir:
        tmp = tempfile.mkdtemp(prefix="genie_onnx_")
        onnx_dir = tmp
    try:
        convert(args.pth, args.ckpt, onnx_dir)
        print("[2/3] 计算参考特征并打包")
        prepare(args.name, onnx_dir, args.ref_wav, args.ref_text,
                args.ref_lang, args.out, args.display_name)
        print("[3/3] 生成 fp32 外置权重")
        ensure_fp32_external(args.out)
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)

    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(args.out) for f in fs)
    print(f"DONE {args.out} {total / 1048576:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
