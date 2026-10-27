#!/usr/bin/env python3
"""校验声音包是否完整、可被 App 加载，并检查 meta 与 ref 特征尺寸是否自洽。

用法:
  python verify_pack.py models/voices/roxy
  python verify_pack.py 洛琪希.zip          # 直接校验待导入的压缩包
  python verify_pack.py models/voices/roxy --no-onnx   # 跳过 ONNX 实际加载

检查项:
  1. meta.properties 存在且可解析
  2. 4 个核心 onnx 存在且非空（v2pp 允许额外 prompt_encoder）
  3. ref/ 特征文件齐全，且字节数与 meta 记录的维度一致
  4. （可选）用 onnxruntime 逐个加载，确认可推理
  5. zip 模式：包内任意层级含 meta.properties 即可，其余按相对路径检查

退出码: 0 全部通过；1 有错误（每条都会打印）
"""
from __future__ import annotations

import argparse
import os
import sys
import zipfile

REQUIRED_ONNX = [
    "t2s_encoder_fp32.onnx",
    "t2s_first_stage_decoder_fp32.onnx",
    "t2s_stage_decoder_fp32.onnx",
    "vits_fp32.onnx",
]
REQUIRED_REF = ["ref/ref_seq.i64", "ref/ref_bert.f32", "ref/ssl.f32"]


def read_meta(path: str) -> dict:
    meta = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            meta[k.strip()] = v.strip()
    return meta


def check_dir(root: str, check_onnx: bool) -> list[str]:
    errs: list[str] = []
    meta_path = os.path.join(root, "meta.properties")
    if not os.path.isfile(meta_path):
        return [f"缺少 meta.properties: {root}"]
    meta = read_meta(meta_path)

    for f in REQUIRED_ONNX:
        p = os.path.join(root, f)
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            errs.append(f"缺少或为空: {f}")

    vtype = meta.get("type", "v2")
    if vtype == "v2pp":
        for f in ("ref/ge.f32", "ref/ge_adv.f32"):
            p = os.path.join(root, f)
            if not os.path.isfile(p) or os.path.getsize(p) == 0:
                errs.append(f"v2pp 包缺少: {f}")
    else:
        p = os.path.join(root, "ref/ref_audio.f32")
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            errs.append("v2 包缺少: ref/ref_audio.f32")

    for f in REQUIRED_REF:
        p = os.path.join(root, f)
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            errs.append(f"缺少或为空: {f}")

    # 尺寸自洽
    try:
        seq_len = int(meta["ref_seq_len"])
        bert_dim = int(meta["ref_bert_dim"])
        ssl_len = int(meta["ssl_len"])
        p = os.path.join(root, "ref/ref_seq.i64")
        if os.path.isfile(p) and os.path.getsize(p) != seq_len * 8:
            errs.append(f"ref_seq.i64 大小 {os.path.getsize(p)} != ref_seq_len({seq_len})*8")
        p = os.path.join(root, "ref/ref_bert.f32")
        if os.path.isfile(p) and os.path.getsize(p) != seq_len * bert_dim * 4:
            errs.append(f"ref_bert.f32 大小 {os.path.getsize(p)} != {seq_len}*{bert_dim}*4")
        p = os.path.join(root, "ref/ssl.f32")
        if os.path.isfile(p) and os.path.getsize(p) != 768 * ssl_len * 4:
            errs.append(f"ssl.f32 大小 {os.path.getsize(p)} != 768*{ssl_len}*4")
    except KeyError as e:
        errs.append(f"meta.properties 缺少字段: {e}")
    except ValueError as e:
        errs.append(f"meta.properties 字段不是数字: {e}")

    if check_onnx:
        try:
            import onnxruntime as ort
        except ImportError:
            print("[warn] 未安装 onnxruntime，跳过 ONNX 加载检查（pip install onnxruntime）")
            ort = None
        if ort is not None:
            for f in REQUIRED_ONNX:
                p = os.path.join(root, f)
                if not os.path.isfile(p):
                    continue
                try:
                    sess = ort.InferenceSession(p, providers=["CPUExecutionProvider"])
                    _ = sess.get_inputs()
                except Exception as e:  # noqa: BLE001
                    errs.append(f"ONNX 加载失败 {f}: {e}")

    return errs


def check_zip(path: str, check_onnx: bool) -> list[str]:
    errs: list[str] = []
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        return [f"不是合法 zip: {e}"]

    bad = z.testzip()
    if bad:
        errs.append(f"zip 内文件损坏: {bad}")

    names = z.namelist()
    meta_entries = [n for n in names if n.endswith("meta.properties")]
    if not meta_entries:
        return errs + ["压缩包里没有 meta.properties"]
    prefix = os.path.dirname(meta_entries[0])
    prefix = (prefix + "/") if prefix else ""

    def has(rel: str) -> bool:
        return prefix + rel in names

    meta = {}
    with z.open(meta_entries[0]) as f:
        for line in f.read().decode("utf-8", "ignore").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                meta[k.strip()] = v.strip()

    for f in REQUIRED_ONNX:
        if not has(f):
            errs.append(f"缺少: {f}")
    for f in REQUIRED_REF:
        if not has(f):
            errs.append(f"缺少: {f}")
    vtype = meta.get("type", "v2")
    if vtype == "v2pp":
        for f in ("ref/ge.f32", "ref/ge_adv.f32"):
            if not has(f):
                errs.append(f"v2pp 包缺少: {f}")
    else:
        if not has("ref/ref_audio.f32"):
            errs.append("v2 包缺少: ref/ref_audio.f32")
    return errs


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="校验声音包（目录或 zip）")
    ap.add_argument("path", help="声音包目录或 zip 文件")
    ap.add_argument("--no-onnx", action="store_true", help="跳过 onnxruntime 加载检查")
    args = ap.parse_args(argv)

    check_onnx = not args.no_onnx
    if os.path.isdir(args.path):
        errs = check_dir(args.path, check_onnx)
    elif os.path.isfile(args.path):
        errs = check_zip(args.path, check_onnx)
    else:
        print(f"路径不存在: {args.path}")
        return 1

    if errs:
        print(f"FAIL {len(errs)} 项问题：")
        for e in errs:
            print("  -", e)
        return 1

    print(f"OK {args.path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
