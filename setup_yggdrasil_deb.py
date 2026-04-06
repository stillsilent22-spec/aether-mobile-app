#!/usr/bin/env python3
"""
setup_yggdrasil_deb.py — Yggdrasil-Binaries aus .deb-Paketen extrahieren.
Ab v0.5.x liefert Yggdrasil keine .tgz mehr, sondern .deb-Pakete.
"""
import hashlib, io, json, os, re, stat, tarfile, urllib.request
from pathlib import Path

GITHUB_API  = "https://api.github.com/repos/yggdrasil-network/yggdrasil-go/releases/latest"
ASSETS_DIR  = Path("app/src/main/assets/yggdrasil")
MANAGER_KT  = Path("app/src/main/java/io/aether/wrapper/YggdrasilManager.kt")

# ABI  ->  deb-Suffix (Debian-Architektur)
ABI_MAP = {
    "arm64-v8a":   "arm64",
    "armeabi-v7a": "armhf",
    "x86_64":      "amd64",
}

def fetch_release():
    req = urllib.request.Request(GITHUB_API, headers={"User-Agent": "aether-setup"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read())
    version = data["tag_name"]
    assets = {a["name"]: a["browser_download_url"] for a in data["assets"]}
    print(f"Version: {version}")
    return version, assets

def extract_binary_from_deb(deb_data: bytes) -> bytes:
    """Parst das ar-Format eines .deb-Archivs und extrahiert das yggdrasil-Binary."""
    if deb_data[:8] != b"!<arch>\n":
        raise RuntimeError("Keine gueltige ar-Datei")
    pos = 8
    while pos < len(deb_data):
        name  = deb_data[pos:pos+16].decode("ascii").rstrip()
        size  = int(deb_data[pos+48:pos+58].decode("ascii").strip())
        pos  += 60
        entry = deb_data[pos:pos+size]
        pos  += size + (size % 2)          # ar-Padding auf gerade Grenze
        if name.startswith("data.tar"):
            try:
                with tarfile.open(fileobj=io.BytesIO(entry)) as tar:
                    for m in tar.getmembers():
                        if m.name.endswith("/yggdrasil") and not m.name.endswith(".conf"):
                            f = tar.extractfile(m)
                            if f:
                                return f.read()
            except Exception as e:
                print(f"  tar-Fehler: {e}")
    raise RuntimeError("Binary nicht im .deb gefunden")

def sha256_file(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def patch_manager(hashes: dict):
    print(f"\nPatch: {MANAGER_KT}")
    content = MANAGER_KT.read_text(encoding="utf-8")
    for abi, digest in hashes.items():
        # Platzhalter ersetzen
        pat = rf'"{re.escape(abi)}"\s+to\s+"PENDING_SHA256_\w+"'
        repl = f'"{abi}"      to "{digest}"'
        content, n = re.subn(pat, repl, content)
        if n == 0:
            # Bereits gepatchten Wert ueberschreiben
            pat2 = rf'"{re.escape(abi)}"\s+to\s+"[a-f0-9]{{64}}"'
            content, n = re.subn(pat2, repl, content)
        status = "OK" if n > 0 else "WARNUNG: nicht gefunden"
        print(f"  {abi}: {digest[:32]}...  [{status}]")
    MANAGER_KT.write_text(content, encoding="utf-8")
    print("  YggdrasilManager.kt aktualisiert.")

def main():
    if not MANAGER_KT.exists():
        print("FEHLER: Bitte aus dem Projektroot ausfuehren.")
        raise SystemExit(1)

    version, release_assets = fetch_release()
    hashes = {}

    for abi, suffix in ABI_MAP.items():
        match = next(
            (n for n in release_assets
             if suffix in n and n.endswith(".deb")
             and "edgeos" not in n and "vyos" not in n
             and "i386"   not in n and "armel" not in n),
            None
        )
        if not match:
            print(f"  WARNUNG: kein Asset fuer {abi} ({suffix})")
            continue
        url = release_assets[match]
        print(f"  [{abi}] Download: {match}")
        with urllib.request.urlopen(url, timeout=120) as r:
            deb_data = r.read()
        print(f"  [{abi}] Extrahiere Binary aus .deb...")
        binary = extract_binary_from_deb(deb_data)
        dest = ASSETS_DIR / abi / "yggdrasil"
        dest.write_bytes(binary)
        dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)
        digest  = sha256_file(binary)
        size_kb = len(binary) // 1024
        hashes[abi] = digest
        print(f"  [{abi}] OK - {size_kb} KB - SHA-256: {digest[:16]}...")

    if not hashes:
        print("\nKeine Binaries geladen.")
        raise SystemExit(1)

    patch_manager(hashes)
    print(f"\nFertig. Yggdrasil {version} fuer: {', '.join(hashes.keys())}")

if __name__ == "__main__":
    main()

