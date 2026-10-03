#!/usr/bin/env python3
"""打包 arm64-v8a / x86_64 / universal 三个 APK 到 dist/{version}/。

用法（在项目根目录或任意位置均可）:
    python tools/package_apks.py               # 打 release 包（默认）
    python tools/package_apks.py --clean       # 先 clean 再打包
    python tools/package_apks.py --debug       # 打 debug 包

产物命名: {app_name}-{abi}.apk，例如 CarroMed-arm64-v8a.apk / CarroMed-universal.apk，
输出目录: dist/{versionName}/。APK 同时被 .gitignore 的 *.apk 规则忽略。

依赖 app/build.gradle.kts 中由 -PabiSplits 开关的 ABI splits 配置，
本脚本打包时自动传入该属性。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET_ABIS = ("arm64-v8a", "x86_64")
# splits 产物中 universal 包的目录名后缀
UNIVERSAL_TAG = "universal"


def fail(msg: str) -> None:
    print(f"[package_apks] ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def read_app_name() -> str:
    """从 values/strings.xml 读 app_name，避免脚本里硬编码。"""
    strings_xml = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
    if not strings_xml.is_file():
        fail(f"找不到 {strings_xml}")
    root = ET.parse(strings_xml).getroot()
    node = root.find("string[@name='app_name']")
    if node is None or not (node.text or "").strip():
        fail("strings.xml 中未找到 app_name")
    return (node.text or "").strip()


def run_gradle(variant: str, clean: bool) -> None:
    gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if not gradlew.is_file():
        fail(f"找不到 {gradlew}")
    task = f"assemble{variant.capitalize()}"
    cmd = [str(gradlew)]
    if clean:
        cmd.append("clean")
    cmd += [task, "-PabiSplits=true"]
    print(f"[package_apks] 运行: {' '.join(cmd[1:])}")
    # 不捕获输出，让 gradle 进度直接打到终端
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        fail(f"gradle {task} 失败，退出码 {result.returncode}")


def read_version_name(variant: str) -> str:
    """从 gradle 产物目录的 output-metadata.json 读 versionName（构建的真实值）。"""
    metadata = ROOT / "app" / "build" / "outputs" / "apk" / variant / "output-metadata.json"
    if not metadata.is_file():
        fail(f"找不到 {metadata}，gradle 可能未生成产物")
    data = json.loads(metadata.read_text(encoding="utf-8"))
    version = data.get("versionName")
    if not version:
        elements = data.get("elements") or []
        version = elements[0].get("versionName") if elements else None
    if not version:
        fail(f"{metadata} 中没有 versionName")
    return str(version)


def collect_apks(variant: str) -> dict[str, Path]:
    """返回 {abi: apk路径}，abi 为 arm64-v8a / x86_64 / universal。"""
    out_dir = ROOT / "app" / "build" / "outputs" / "apk" / variant
    suffix = f"-{variant}.apk"
    result: dict[str, Path] = {}
    for abi in TARGET_ABIS:
        apk = out_dir / f"app-{abi}{suffix}"
        if not apk.is_file():
            fail(f"未找到 {abi} 产物: {apk}")
        result[abi] = apk
    universal = out_dir / f"app-{UNIVERSAL_TAG}{suffix}"
    if not universal.is_file():
        fail(f"未找到 universal 产物: {universal}")
    result[UNIVERSAL_TAG] = universal
    return result


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="打包 ABI 拆分 APK 到 dist/{version}/")
    parser.add_argument("--debug", action="store_true", help="打 debug 包（默认 release）")
    parser.add_argument("--clean", action="store_true", help="打包前先 clean")
    args = parser.parse_args()

    variant = "debug" if args.debug else "release"
    app_name = read_app_name()
    run_gradle(variant, args.clean)
    version = read_version_name(variant)
    apks = collect_apks(variant)

    dist_dir = ROOT / "dist" / version
    dist_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n[package_apks] 版本 {version}，输出目录: {dist_dir}")
    for abi, src in apks.items():
        dest = dist_dir / f"{app_name}-{abi}.apk"
        shutil.copy2(src, dest)
        size_mb = dest.stat().st_size / (1 << 20)
        print(f"  {dest.name:32s} {size_mb:7.2f} MB  sha256={sha256(dest)}")

    if variant == "release":
        signed = all("unsigned" not in p.name.lower() for p in apks.values())
        if not signed:
            print("[package_apks] 注意: 存在未签名 release 包（缺少 key.properties 或 storeFile）")
    print("[package_apks] 完成")


if __name__ == "__main__":
    main()
