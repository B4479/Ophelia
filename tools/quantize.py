#!/usr/bin/env python3
"""声音包 int8 量化：融合 MatMul+Transpose 为 Gemm，再动态量化 T2S 解码器。

用法:
  python quantize.py --src models/voices/roxy_fp32 --dst models/voices/roxy [--vits]

说明:
  - 默认只量化 t2s_first_stage_decoder / t2s_stage_decoder（编码器的 VQ codebook 与量化不兼容）
  - --vits 会额外量化 vits 图（体积更小，但音质有损，需自行 A/B）
  - 产物文件名不变，App / CLI 可直接加载

依赖: onnx、onnxruntime（见 requirements.txt）
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys


def fuse(src: str, dst: str) -> int:
    import onnx
    from onnx import helper

    m = onnx.load(src)
    g = m.graph
    inits = {i.name for i in g.initializer}
    producer = {o: n for n in g.node for o in n.output}
    uses: dict[str, int] = {}
    for n in g.node:
        for i in n.input:
            uses[i] = uses.get(i, 0) + 1
    for o in g.output:
        uses[o.name] = uses.get(o.name, 0) + 1

    remove = set()
    out_nodes = []
    fused = 0
    for n in g.node:
        if n.op_type == "MatMul":
            b = n.input[1]
            p = producer.get(b)
            if p is not None and p.op_type == "Transpose" and p.input[0] in inits:
                perm = None
                for a in p.attribute:
                    if a.name == "perm":
                        perm = list(a.ints)
                if perm == [1, 0]:
                    out_nodes.append(helper.make_node(
                        "Gemm", [n.input[0], p.input[0]], list(n.output), name=n.name + "_f", transB=1))
                    uses[b] -= 1
                    if uses[b] <= 0:
                        remove.add(p.name)
                    fused += 1
                    continue
        out_nodes.append(n)
    del g.node[:]
    g.node.extend([n for n in out_nodes if n.name not in remove])
    onnx.checker.check_model(m)
    onnx.save(m, dst, save_as_external_data=False)
    return fused


def quantize(src: str, dst: str, do_vits: bool = False) -> None:
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        raise SystemExit("缺少 onnxruntime，请先 pip install -r requirements.txt")

    if os.path.exists(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    names = ["t2s_first_stage_decoder_fp32.onnx", "t2s_stage_decoder_fp32.onnx"]
    if do_vits:
        names.append("vits_fp32.onnx")

    for fn in names:
        p = os.path.join(dst, fn)
        if not os.path.exists(p):
            print("skip", fn)
            continue
        fused = p + ".fused.onnx"
        n = fuse(p, fused)
        q = p + ".q.onnx"
        quantize_dynamic(fused, q, weight_type=QuantType.QInt8, per_channel=True,
                         op_types_to_quantize=["MatMul", "Gemm"])
        os.replace(q, p)
        os.remove(fused)
        print(fn, "fused", n, "->", os.path.getsize(p) // 1048576, "MB")

    # 解码器权重已内联，外置 bin 不再需要
    for fn in ["t2s_shared_fp32.bin", "t2s_shared_fp16.bin", "vits_fp16.bin"]:
        p = os.path.join(dst, fn)
        if os.path.exists(p):
            os.remove(p)
            print("removed", fn)
    if do_vits:
        p = os.path.join(dst, "vits_fp32.bin")
        if os.path.exists(p):
            os.remove(p)
            print("removed", fn)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="声音包 int8 量化")
    ap.add_argument("--src", required=True, help="fp32 声音包目录")
    ap.add_argument("--dst", required=True, help="输出目录（会被覆盖）")
    ap.add_argument("--vits", action="store_true", help="同时量化 vits（音质有损）")
    args = ap.parse_args(argv)

    quantize(args.src, args.dst, args.vits)
    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(args.dst) for f in fs)
    print("TOTAL", round(total / 1048576, 1), "MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
