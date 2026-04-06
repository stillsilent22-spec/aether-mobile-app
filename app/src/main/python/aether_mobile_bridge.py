# Aether Mobile Bridge — Version 4.20 (AEF & DNA v1 PROTOCOL)
import hashlib
import json
import os
import random
import zlib
import struct
import numpy as np
from pathlib import Path
from aelab_motor import AELabMotor
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CASCADE_VERSION = "4"
SWARM_PORT = 7389
aelab_local = AELabMotor(tier="MID")

def run_aelab_evolution(intensity):
    """Führt Evolution durch und verpackt das Ergebnis in einen AEF-Container."""
    # 1. Rohdaten simulieren
    target_stream = os.urandom(256)
    original_hash = hashlib.sha256(target_stream).digest()

    # 2. Evolution (DNA v1)
    dna_hash, trust, k_ratio = aelab_local.evolve(target_stream)

    if dna_hash:
        # 3. AEF Encoding
        aef_data = encode_aef(target_stream, dna_hash, trust, k_ratio)

        return {
            "status": "STABLE_INVARIANT",
            "vault_size": len(aelab_local.vault),
            "trust": round(trust, 4),
            "k_ratio": round(k_ratio, 4),
            "aef_hex": aef_data.hex()[:128] + "...",
            "dna_ref": dna_hash[:12]
        }

    return {"status": "VOLATILE_SIGNAL", "trust": round(trust, 4)}

def encode_aef(raw_data, dna_hash, trust, k_ratio):
    """Erzeugt einen binären AEF-Container (Version 4.0)."""
    magic = b"AEF\x00"
    genesis_ref = hashlib.sha256(b"genesis").digest()
    original_size = len(raw_data)
    original_hash = hashlib.sha256(raw_data).digest()
    filetype = 0x01 # Aether Signal

    # Header packen (Magic, Genesis, Size, Hash, Type)
    header = magic + genesis_ref + struct.pack(">I", original_size) + original_hash + struct.pack(">I", filetype)

    # AnchorMap (1 Entry: DNA_HASH)
    # UUID (16) + Vault_Ref (32) + pos(4) + weight(4) + type(4)
    anchor_count = struct.pack(">I", 1)
    anchor_entry = os.urandom(16) + bytes.fromhex(dna_hash) + struct.pack(">fff", 0.0, 1.0, 0.0)
    anchor_map = anchor_count + anchor_entry

    # DeltaLayer (Zlib + AES Dummy)
    delta_raw = b"REST_DELTA_DATA" # In echt: raw_data - predicted
    delta_comp = zlib.compress(delta_raw)
    # AES-GCM (Key: random für Demo)
    key = AESGCM.generate_key(bit_length=256)
    aesgcm = AESGCM(key)
    nonce = os.urandom(12)
    delta_layer = aesgcm.encrypt(nonce, delta_comp, None)

    # TrustMetadata
    # trust(4) + flags(4) + e_lambda(4) + signature(64)
    e_lambda = random.uniform(0.1, 1.0)
    metadata = struct.pack(">fIf", trust, 0x03FF, e_lambda) + os.urandom(64) # Signature-Mock

    eof = b"\x45\x4F\x46\x41"

    return header + anchor_map + delta_layer + metadata + eof

def process_remote_task(task_json, intensity):
    """Verarbeitet eingehende TCP-Tasks im AEF/DNA Verbund."""
    try:
        data = json.loads(task_json)
        payload = bytes.fromhex(data["raw_b64"]) if "raw_b64" in data else os.urandom(128)

        dna_hash, trust, k_ratio = aelab_local.evolve(payload)

        if dna_hash:
            return json.dumps({
                "type": "compute_response",
                "dna_hash": dna_hash,
                "trust": trust,
                "aef_checksum": hashlib.sha256(dna_hash.encode()).hexdigest()[:8]
            })
    except Exception as e:
        return json.dumps({"error": str(e)})
    return json.dumps({"status": "low_fitness"})

def create_user_account(storage_path, username):
    id_mgr = AetherIdentity(storage_path)
    return id_mgr.create_account(username, "no_pass")

class AetherIdentity:
    def __init__(self, storage_path):
        self.data_dir = Path(storage_path)
        if not self.data_dir.exists(): self.data_dir.mkdir(parents=True)
        self.id_file = self.data_dir / "identity.json"
        self.identity = self._load()

    def _load(self):
        if self.id_file.is_file(): return json.loads(self.id_file.read_text())
        return None

    def create_account(self, username, password):
        if self.identity: return self.identity
        priv = ed25519.Ed25519PrivateKey.generate()
        pub = priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        priv_hex = priv.private_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw, serialization.NoEncryption()).hex()
        node_id = hashlib.sha256(pub).hexdigest()[:16]
        self.identity = {"user": username, "node_id": node_id, "pub": pub.hex(), "priv": priv_hex}
        self.id_file.write_text(json.dumps(self.identity))
        return self.identity
