#!/usr/bin/env python3
"""把已签名 APK 重新做成「ELF + zip 双 16KB 对齐」的包。

Android 15+ 的 16KB 页设备要求：
  1. .so 的 ELF LOAD 段按 16KB 对齐   （NDK/依赖库侧解决）
  2. 未压缩 .so 在 APK 内的偏移按 16KB 对齐（zipalign -P 16）

zipalign 会破坏签名，所以流程是：zipalign → 用原密钥重新签名 → 校验。

用法:
  python tools/align16k.py --in app-release-unsigned-signed.apk --out out.apk \
      --keystore ophelia-release.jks --alias ophelia \
      --storepass '***' --keypass '***'
  python tools/align16k.py --in app-debug.apk --out out.apk \
      --keystore ~/.android/debug.keystore --alias androiddebugkey \
      --storepass android --keypass android

依赖：Android SDK build-tools（zipalign、apksigner）与 JDK。
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile

SDK_HINTS = [
    os.environ.get("ANDROID_HOME", ""),
    os.environ.get("ANDROID_SDK_ROOT", ""),
    os.path.expanduser("~/Android/Sdk"),
]


def find_tool(name: str) -> str:
    """在 build-tools 里找最新的 zipalign / apksigner。"""
    candidates = []
    for root in SDK_HINTS:
        if not root:
            continue
        bt = os.path.join(root, "build-tools")
        if not os.path.isdir(bt):
            continue
        for ver in sorted(os.listdir(bt), reverse=True):
            for fn in (name, name + ".bat", name + ".exe"):
                p = os.path.join(bt, ver, fn)
                if os.path.isfile(p):
                    candidates.append(p)
    if not candidates:
        raise SystemExit(f"找不到 {name}，请设置 ANDROID_HOME 或把 build-tools 放进 PATH")
    return candidates[0]


def run(cmd: list[str]) -> None:
    print("$", " ".join(cmd))
    r = subprocess.run(cmd)
    if r.returncode != 0:
        raise SystemExit(f"命令失败（{r.returncode}）: {cmd[0]}")


def check_zip_alignment(zipalign: str, apk: str) -> bool:
    """用 zipalign 自身做校验（它读的是本地文件头，含对齐填充）。"""
    r = subprocess.run([zipalign, "-c", "-P", "16", "4", apk],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout.strip()[:500])
        return False
    return True


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="APK 双 16KB 对齐 + 重签名")
    ap.add_argument("--in", dest="src", required=True, help="已签名 APK")
    ap.add_argument("--out", dest="dst", required=True, help="输出 APK")
    ap.add_argument("--keystore", required=True)
    ap.add_argument("--alias", required=True)
    ap.add_argument("--storepass", required=True)
    ap.add_argument("--keypass", default=None)
    ap.add_argument("--min-sdk", default="29")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.src):
        raise SystemExit(f"输入不存在: {args.src}")

    zipalign = find_tool("zipalign")
    apksigner = find_tool("apksigner")

    aligned = args.dst + ".aligned"
    print("[1/3] zipalign -P 16")
    run([zipalign, "-P", "16", "-f", "4", args.src, aligned])

    print("[2/3] 重新签名")
    os.makedirs(os.path.dirname(os.path.abspath(args.dst)), exist_ok=True)
    sign_cmd = [
        apksigner, "sign",
        "--ks", args.keystore,
        "--ks-key-alias", args.alias,
        "--ks-pass", f"pass:{args.storepass}",
        "--key-pass", f"pass:{args.keypass or args.storepass}",
        "--min-sdk-version", args.min_sdk,
        "--out", args.dst,
        aligned,
    ]
    run(sign_cmd)
    os.remove(aligned)

    print("[3/3] 校验")
    run([apksigner, "verify", "--print-certs", args.dst])
    if not check_zip_alignment(zipalign, args.dst):
        raise SystemExit("zip 内 .so 仍未 16KB 对齐")
    print("OK：ELF（构建期）+ zip（本次）双 16KB 对齐")
    return 0


if __name__ == "__main__":
    sys.exit(main())
