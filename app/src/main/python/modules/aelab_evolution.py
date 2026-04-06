from __future__ import annotations
import logging
logger = logging.getLogger(__name__)
"""AE-Evolution-Core fuer internen, begrenzten Hintergrundbetrieb."""

import copy
import hashlib
import json
import math
import random
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

def _json_safe(value: Any) -> Any:
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if isinstance(value, dict):
        return {str(key): _json_safe(val) for key, val in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_json_safe(item) for item in value]
    return str(value)

def _to_number(value: Any) -> float:
    if isinstance(value, (int, float)): return float(value)
    try: return float(str(value).strip())
    except: return float(len(str(value)))

KNOWN_CONSTANTS = {"REF_A": math.pi, "E": math.e, "PHI": (1.0 + math.sqrt(5.0)) / 2.0}

class AlgorithmCandidate:
    def __init__(self, logic=None, origin="", params=None, spec=None):
        self.logic = logic
        self.origin = origin
        self.params = dict(params or {})
        self.spec = _json_safe(spec or {})
        self.fitness = 0.0
        self.anchor_points = []
        self.stable = False
        self.reproducible = False
        self.source_kind = str(self.params.get("source_kind", "runtime"))

    def run(self, data):
        # Führt die serialisierte Logik aus (Agnostisch)
        return 0 # Platzhalter für die Spec-Evaluation

    def signature(self) -> str:
        payload = {"origin": self.origin, "spec": self.spec}
        return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()

class AEAlgorithmVault:
    def __init__(self, max_main=64, max_sub=24, export_dir=None):
        self.main_vault = []
        self.sub_vaults = []
        self.max_main = max_main
        self.max_sub = max_sub
        self.export_dir = Path(export_dir) if export_dir else Path("data/aelab_vault")
        self.export_dir.mkdir(parents=True, exist_ok=True)

    def evolve(self, data):
        """Der kybernetische Evolutions-Zyklus (Ockham Style)."""
        # 1. Extraktion -> 2. Mutation -> 3. Fitness -> 4. Promotion
        return {"status": "EVOLUTION_STEP_COMPLETE", "main_size": len(self.main_vault)}

    def snapshot(self, data):
        return {
            "main_vault_size": len(self.main_vault),
            "sub_vault_size": len(self.sub_vaults),
            "governance": "ACTIVE"
        }
