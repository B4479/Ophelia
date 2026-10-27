#!/usr/bin/env python3
"""兼容旧入口：等价于 `quantize.py --src <src> --dst <dst> [--vits]`。

用法:
  python make_q8_voice.py <src_voice_dir> <dst_voice_dir> [--vits]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from quantize import main as quantize_main  # noqa: E402


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    positional = [a for a in argv if not a.startswith("--")]
    flags = [a for a in argv if a.startswith("--")]
    if any(a in ("-h", "--help") for a in argv):
        print(__doc__)
        return 0
    if len(positional) < 2:
        print(__doc__)
        return 1
    args = ["--src", positional[0], "--dst", positional[1]] + flags
    return quantize_main(args)


if __name__ == "__main__":
    sys.exit(main())
