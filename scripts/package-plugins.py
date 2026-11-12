#!/usr/bin/env python3
"""把 plugin-samples 下的示例插件打成可导入的 zip 包。

用法：
    python3 scripts/package-plugins.py                 # 打包全部示例
    python3 scripts/package-plugins.py capability-demo # 只打指定目录
    python3 scripts/package-plugins.py --clean         # 先清空 plugin-packages/*.zip

输出：plugin-packages/<插件ID>-<版本>.zip，包根目录直接包含 plugin.json。
脚本只做打包，不修改清单、不执行插件代码；格式与限额以宿主 PluginPackageReader 为准。
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAMPLES_DIR = ROOT / "plugin-samples"
OUTPUT_DIR = ROOT / "plugin-packages"

# 与宿主安装器保持同一量级的上限，避免打出必然被拒绝的包。
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 512
MAX_ENTRY_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024

SKIPPED_DIRS = {".git", ".gradle", "node_modules", ".venv", "__pycache__", ".idea"}
SKIPPED_NAMES = {".DS_Store", "Thumbs.db"}


def fail(message: str) -> None:
    print(f"错误: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_manifest(sample_dir: Path) -> dict:
    manifest_path = sample_dir / "plugin.json"
    if not manifest_path.is_file():
        fail(f"{sample_dir} 缺少 plugin.json")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"读取 {manifest_path} 失败: {exc}")
    if not isinstance(manifest, dict):
        fail(f"{manifest_path} 根节点必须是对象")
    plugin_id = manifest.get("id")
    version = manifest.get("version")
    if not isinstance(plugin_id, str) or not plugin_id:
        fail(f"{manifest_path} 缺少字符串 id")
    if not isinstance(version, str) or not version:
        fail(f"{manifest_path} 缺少字符串 version")
    return manifest


def iter_package_files(sample_dir: Path):
    for path in sorted(sample_dir.rglob("*")):
        if path.is_symlink():
            fail(f"不支持符号链接: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(sample_dir)
        if relative.name in SKIPPED_NAMES:
            continue
        if any(part in SKIPPED_DIRS for part in relative.parts):
            continue
        if path.stat().st_size > MAX_ENTRY_BYTES:
            fail(f"单文件超过 4 MiB: {relative}")
        yield path, relative.as_posix()


def package_sample(sample_dir: Path, output_dir: Path) -> Path:
    manifest = load_manifest(sample_dir)
    plugin_id = manifest["id"]
    version = manifest["version"]
    if "/" in plugin_id or "\\" in plugin_id or plugin_id in {".", ".."}:
        fail(f"非法插件 id: {plugin_id}")

    files = list(iter_package_files(sample_dir))
    if not files:
        fail(f"{sample_dir} 没有可打包的文件")
    if len(files) > MAX_ENTRIES:
        fail(f"{sample_dir} 文件数超过 {MAX_ENTRIES}")

    target = output_dir / f"{plugin_id}-{version}.zip"
    temp = target.with_name(target.name + ".tmp")
    total_bytes = 0
    try:
        with zipfile.ZipFile(temp, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path, name in files:
                total_bytes += path.stat().st_size
                if total_bytes > MAX_TOTAL_BYTES:
                    fail(f"{sample_dir} 解压总量超过 32 MiB")
                archive.write(path, name)
        if temp.stat().st_size > MAX_ARCHIVE_BYTES:
            fail(f"{target.name} 超过 16 MiB")
        temp.replace(target)
    finally:
        if temp.exists():
            temp.unlink()
    print(f"打包 {sample_dir.name} -> {target.relative_to(ROOT)} ({len(files)} 个文件)")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description="打包 plugin-samples 下的示例插件")
    parser.add_argument("samples", nargs="*", help="只打包指定目录名；省略则打包全部")
    parser.add_argument("--clean", action="store_true", help="打包前删除 plugin-packages 下已有的 zip")
    args = parser.parse_args()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    if args.clean:
        for old in OUTPUT_DIR.glob("*.zip"):
            old.unlink()

    if args.samples:
        sample_dirs = [SAMPLES_DIR / name for name in args.samples]
    else:
        sample_dirs = sorted(
            path for path in SAMPLES_DIR.iterdir() if path.is_dir() and (path / "plugin.json").is_file()
        )
    if not sample_dirs:
        fail(f"{SAMPLES_DIR} 下没有找到示例插件")

    for sample_dir in sample_dirs:
        if not sample_dir.is_dir():
            fail(f"示例目录不存在: {sample_dir}")
        package_sample(sample_dir, OUTPUT_DIR)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
