#!/usr/bin/env python3
"""把 Genie ONNX 角色目录 + 参考音频，打包成 App 可直接加载的「声音包」。

用法:
  python prepare_character.py --name roxy --onnx-dir out/onnx \
      --ref-wav ref.wav --ref-text "参考句原文" --ref-lang ja --out models/voices/roxy

产物（--out 目录）:
  meta.properties
  t2s_*.onnx / *.bin / vits_*.onnx / *.bin     从 --onnx-dir 复制（硬链接优先）
  ref/ref_seq.i64     int64   [N]
  ref/ref_bert.f32    float32 [N,1024]
  ref/ssl.f32         float32 [1,768,T]
  ref/ref_audio.f32   float32 [L]        （v2 用）
  ref/ge.f32, ge_adv.f32                 （v2ProPlus 用）

依赖: genie-tts、numpy（见 requirements.txt）
环境变量: GENIE_DATA_DIR 指向 GenieData 目录（首次运行 genie 会用到）
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys


def prepare(name: str, onnx_dir: str, ref_wav: str, ref_text: str, ref_lang: str,
            out_dir: str, display_name: str | None = None) -> dict:
    """执行打包，返回 meta 字典。"""
    try:
        import numpy as np
        import genie_tts as genie
        from genie_tts.ModelManager import model_manager
        from genie_tts.Utils.Shared import context
    except ImportError as e:  # pragma: no cover - 依赖缺失时给出人话提示
        raise SystemExit(
            f"缺少依赖: {e}\n请先 pip install -r requirements.txt，并设置 GENIE_DATA_DIR"
        )

    os.makedirs(os.path.join(out_dir, "ref"), exist_ok=True)

    genie.load_character(name, onnx_dir, language="zh")
    genie.set_reference_audio(name, ref_wav, ref_text, language=ref_lang)

    raf = context.current_prompt_audio
    ref_seq = np.asarray(raf.phonemes_seq, dtype=np.int64)
    ref_bert = np.asarray(raf.text_bert, dtype=np.float32)
    ssl = np.asarray(raf.ssl_content, dtype=np.float32)
    audio = np.asarray(raf.audio_32k, dtype=np.float32)

    model = model_manager.get(name)
    is_pp = model is not None and model.PROMPT_ENCODER is not None
    if is_pp:
        raf.update_global_emb(prompt_encoder=model.PROMPT_ENCODER)
        ge = np.asarray(raf.global_emb, dtype=np.float32)
        ge_adv = np.asarray(raf.global_emb_advanced, dtype=np.float32)

    # 复制模型文件（硬链接优先，跨盘自动回退为复制）
    for f in sorted(os.listdir(onnx_dir)):
        if f.endswith((".onnx", ".bin", ".data")):
            src = os.path.join(onnx_dir, f)
            dst = os.path.join(out_dir, f)
            if not os.path.exists(dst):
                try:
                    os.link(src, dst)
                except OSError:
                    shutil.copy2(src, dst)

    rd = os.path.join(out_dir, "ref")
    ref_seq.tofile(os.path.join(rd, "ref_seq.i64"))
    ref_bert.tofile(os.path.join(rd, "ref_bert.f32"))
    ssl.tofile(os.path.join(rd, "ssl.f32"))
    if is_pp:
        ge.tofile(os.path.join(rd, "ge.f32"))
        ge_adv.tofile(os.path.join(rd, "ge_adv.f32"))
    else:
        audio.reshape(-1).tofile(os.path.join(rd, "ref_audio.f32"))

    meta = {
        "name": display_name or name,
        "text_lang": "zh",
        "ref_lang": ref_lang,
        "ref_text": ref_text,
        "type": "v2pp" if is_pp else "v2",
        "sample_rate": 32000,
        "ref_seq_len": int(ref_seq.shape[1]),
        "ref_bert_dim": int(ref_bert.shape[1]),
        "ssl_shape": [int(x) for x in ssl.shape],
        "ssl_len": int(ssl.shape[2]),
        "ref_audio_len": int(audio.reshape(-1).shape[0]),
    }
    with open(os.path.join(out_dir, "meta.properties"), "w", encoding="utf-8") as f:
        for k, v in meta.items():
            f.write(f"{k}={v}\n")

    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(out_dir) for f in fs)
    print(f"OK {out_dir} {total / 1048576:.1f} MB")
    print(json.dumps(meta, ensure_ascii=False, indent=2))
    return meta


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Genie ONNX + 参考音频 → App 声音包")
    ap.add_argument("--name", required=True, help="包内标识（ASCII，如 roxy）")
    ap.add_argument("--onnx-dir", required=True, help="genie.convert_to_onnx 的输出目录")
    ap.add_argument("--ref-wav", required=True, help="参考音频 wav（3–10 秒）")
    ap.add_argument("--ref-text", required=True, help="参考音频对应的原文")
    ap.add_argument("--ref-lang", default="zh", help="参考音频语言：zh/ja/en/ko（默认 zh）")
    ap.add_argument("--out", required=True, help="输出声音包目录")
    ap.add_argument("--display-name", default=None, help="显示名（默认同 --name）")
    args = ap.parse_args(argv)

    prepare(args.name, args.onnx_dir, args.ref_wav, args.ref_text,
            args.ref_lang, args.out, args.display_name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
