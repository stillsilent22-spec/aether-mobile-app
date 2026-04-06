#!/usr/bin/env python3
"""
setup_yggdrasil.py — Aether Mobile: Yggdrasil-Binaries einrichten

Lädt die offiziellen statischen Yggdrasil-Binaries von GitHub,
legt sie in die korrekten Assets-Verzeichnisse und patcht
YggdrasilManager.kt mit den echten SHA-256-Hashes.

Einmalig ausführen, dann APK bauen.

Voraussetzungen:
  - Python 3.8+
  - Internetverbindung
  - Script aus dem Projektroot ausführen:
      python setup_yggdrasil.py
"""

import hashlib
import json
import os
import platform
import re
import stat
import sys
import tarfile
import urllib.request
from pathlib import Path

GITHUB_API = "https://api.github.com/repos/yggdrasil-network/yggdrasil-go/releases/latest"

# Yggdrasil Release-Dateinamen → Android ABI
ABI_MAP = {
    "linux-arm64":  "arm64-v8a",
    "linux-arm":    "armeabi-v7a",
    "linux-amd64":  "x86_64",
}

ASSETS_DIR = Path("app/src/main/assets/yggdrasil")
MANAGER_KT = Path("app/src/main/java/io/aether/wrapper/YggdrasilManager.kt")


def fetch_latest_release():
    print("GitHub API: neueste Yggdrasil-Version abrufen...")
    req = urllib.request.Request(GITHUB_API, headers={"User-Agent": "aether-setup"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read())
    version = data["tag_name"]
    assets = {a["name"]: a["browser_download_url"] for a in data["assets"]}
    print(f"  Version: {version}")
    return version, assets


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def download_and_extract(url: str, abi: str, version: str) -> str:
    dest_dir = ASSETS_DIR / abi
    dest_bin = dest_dir / "yggdrasil"
    tgz_path = dest_dir / "yggdrasil.tgz"

    print(f"  [{abi}] Download: {url.split('/')[-1]}")
    urllib.request.urlretrieve(url, tgz_path)

    print(f"  [{abi}] Entpacken...")
    with tarfile.open(tgz_path, "r:gz") as tar:
        # Yggdrasil-Binary heißt im Archiv "yggdrasil" (kein Suffix auf Linux)
        binary_member = next(
            (m for m in tar.getmembers() if m.name.endswith("yggdrasil") and not m.name.endswith(".conf")),
            None
        )
        if binary_member is None:
            raise RuntimeError(f"Binary 'yggdrasil' nicht im Archiv gefunden für {abi}")
        binary_member.name = "yggdrasil"
        tar.extract(binary_member, dest_dir)

    tgz_path.unlink()

    # Executable-Flag setzen (wichtig für Android ProcessBuilder)
    dest_bin.chmod(dest_bin.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)

    digest = sha256_file(dest_bin)
    size_kb = dest_bin.stat().st_size // 1024
    print(f"  [{abi}] OK — {size_kb} KB — SHA-256: {digest[:16]}...")
    return digest


def patch_yggdrasil_manager(hashes: dict):
    print(f"\nPatch: {MANAGER_KT}")
    content = MANAGER_KT.read_text(encoding="utf-8")

    for abi, digest in hashes.items():
        placeholder_pattern = rf'"{re.escape(abi)}"\s+to\s+"PENDING_SHA256_\w+"'
        replacement = f'"{abi}"      to "{digest}"'
        content, n = re.subn(placeholder_pattern, replacement, content)
        if n == 0:
            # Schon gepatchter Wert — überschreiben
            already_pattern = rf'"{re.escape(abi)}"\s+to\s+"[a-f0-9]{{64}}"'
            content, n = re.subn(already_pattern, replacement, content)
        if n > 0:
            print(f"  {abi}: {digest[:32]}...")
        else:
            print(f"  WARNUNG: Kein Platzhalter für {abi} gefunden — manuell eintragen!")

    MANAGER_KT.write_text(content, encoding="utf-8")
    print("  YggdrasilManager.kt aktualisiert.")


def main():
    # Sicherstellen dass wir im Projektroot sind
    if not MANAGER_KT.exists():
        print("FEHLER: Script muss aus dem Projektroot ausgeführt werden.")
        print(f"  Erwartet: {MANAGER_KT}")
        sys.exit(1)

    try:
        version, release_assets = fetch_latest_release()
    except Exception as e:
        print(f"FEHLER beim Abrufen der Release-Info: {e}")
        sys.exit(1)

    hashes = {}
    for suffix, abi in ABI_MAP.items():
        # Suche nach passendem Asset (z.B. yggdrasil-v0.5.8-linux-arm64.tgz)
        match = next(
            (name for name in release_assets if suffix in name and name.endswith(".tgz")),
            None
        )
        if match is None:
            print(f"  WARNUNG: Kein Release-Asset für {abi} ({suffix}) gefunden — überspringe.")
            continue
        try:
            digest = download_and_extract(release_assets[match], abi, version)
            hashes[abi] = digest
        except Exception as e:
            print(f"  FEHLER bei {abi}: {e}")

    if not hashes:
        print("\nKeine Binaries geladen — abgebrochen.")
        sys.exit(1)

    patch_yggdrasil_manager(hashes)

    print("\n✓ Fertig. Jetzt in Android Studio:")
    print("  Build → Build App Bundle(s) / APK(s) → Build APK(s)")
    print(f"\n  Yggdrasil {version} integriert für: {', '.join(hashes.keys())}")


if __name__ == "__main__":
    main()
