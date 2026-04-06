# AETHER UNIVERSAL CORE - CASCADE V4.1 (OCKHAM STYLE)
# Author: Kevin Hannemann | Identity & Governance Layer
# Status: April 2026

import numpy as np
import math
import hashlib
import json
import os
from collections import Counter

class AetherIdentity:
    """
    Koppelt Nutzername mit Public ID.
    Privacy: PBKDF2-Key-Derivation (Passwort/Hardware-Seed bleiben LOKAL).
    """
    def __init__(self, storage_path):
        self.id_path = os.path.join(storage_path, "identity.json")
        self.data = self._load()

    def _load(self):
        if os.path.exists(self.id_path):
            with open(self.id_path, 'r') as f:
                return json.load(f)
        return None

    def create(self, username, password, hw_seed):
        """
        Erstellt eine neue Identität.
        NUR der öffentliche Teil (Username/PubID) ist für den Swarm sichtbar.
        """
        # Key Stretching (Ockham: Sicherer Key aus schwachen Quellen)
        salt = hw_seed.encode()
        key = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 100000)

        private_key = key.hex()
        public_key = hashlib.sha256(private_key.encode()).hexdigest()

        self.data = {
            "user": username,
            "pub_id": public_key,
            "addr": f"200:aether:{public_key[:16]}"
        }

        with open(self.id_path, 'w') as f:
            json.dump(self.data, f)

        return self.data

    def get_swarm_claim(self):
        """
        Dient zur Eindeutigkeitsprüfung im Netzwerk.
        Keine privaten Daten enthalten.
        """
        if not self.data: return None
        return {
            "op": "NAME_CLAIM",
            "username": self.data["user"],
            "pub_id": self.data["pub_id"]
        }

class AetherCascade:
    @staticmethod
    def calculate(data):
        n = len(data)
        counts = Counter(data)
        h = -sum((c/n) * math.log(c/n, 2) for c in counts.values())
        noether = 1.0 - abs(h - (8.0 - h)) / 8.0
        trust = (h / 8.0 + noether + 0.5) / 3.0
        return {"h": h, "trust": trust, "valid": trust >= 0.65}

def register_node(storage_dir, username, password, hw_id):
    id_mgr = AetherIdentity(storage_dir)
    return id_mgr.create(username, password, hw_id)

def run_aether_cycle(storage_dir, hw_id="android_node"):
    id_mgr = AetherIdentity(storage_dir)
    if not id_mgr.data:
        return {"status": "REGISTER_REQUIRED"}

    cascade = AetherCascade()
    raw_signal = os.urandom(1024)
    metrics = cascade.calculate(raw_signal)

    return {
        "identity": id_mgr.data["user"],
        "pub_id": id_mgr.data["pub_id"][:16],
        "address": id_mgr.data["addr"],
        "metrics": metrics,
        "swarm_status": "CLAIM_ACTIVE"
    }
