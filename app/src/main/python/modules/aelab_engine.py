from __future__ import annotations
import logging
logger = logging.getLogger(__name__)

import hashlib
import threading
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

from .aelab_motor import (
    AEEvolver, Node, fitness_drop,
    node_size, node_depth, has_anchor,
)
from .aelab_vault import AEVault, tree_signature

# ---------------------------------------------------------------------------
# Globaler Vault (lazy-init, thread-safe)
# ---------------------------------------------------------------------------
_vault: Optional[AEVault] = None
_vault_lock = threading.Lock()

_MAX_SAMPLE = 512

def initialize(vault_path: str = "data/aelab_vault") -> None:
    global _vault
    with _vault_lock:
        if _vault is None:
            Path(vault_path).mkdir(parents=True, exist_ok=True)
            _vault = AEVault(root=vault_path, max_main=128, max_sub=256)

def analyze(raw: bytes) -> dict:
    """
    Algorithmisch-strukturelle Analyse eines Byte-Blocks.
    """
    if not raw:
        return _empty_result()

    sample = raw[:_MAX_SAMPLE]

    try:
        _ensure_initialized()

        with _vault_lock:
            seed = _vault.best_seed() if _vault else None

        evolved = False
        score: float = 0.0
        lr: float = 0.0
        best_tree: Optional[Node] = None
        selected_entry: Optional[Dict[str, Any]] = None
        vault_state: Dict[str, Any] = {}

        if seed is not None:
            score, lr, _ = fitness_drop(seed, sample)
            best_tree = seed
            if score < 0.05:
                best_tree, score, lr = _quick_evolve(sample)
                evolved = True
        else:
            best_tree, score, lr = _quick_evolve(sample)
            evolved = True

        if evolved and best_tree is not None and _vault is not None:
            with _vault_lock:
                stored = _vault.store(
                    best_tree,
                    fitness=score,
                    lossless=lr
                )
                selected_entry = _entry_payload(stored)
                vault_state = _vault_status_locked()
        elif best_tree is not None and _vault is not None:
            with _vault_lock:
                sig = tree_signature(best_tree)
                selected_entry = _entry_payload(_vault._entries.get(sig))
                vault_state = _vault_status_locked()

        return {
            "fitness":    round(score, 6),
            "lossless":   round(lr, 4),
            "nodes":      node_size(best_tree) if best_tree else 0,
            "depth":      node_depth(best_tree) if best_tree else 0,
            "has_anchor": has_anchor(best_tree) if best_tree else False,
            "evolved":    evolved,
            "signature":  tree_signature(best_tree) if best_tree else "",
            "selected_seed": selected_entry or {},
            "vault": vault_state,
            "commit_allowed": lr >= 0.95 and (has_anchor(best_tree) if best_tree else False),
        }

    except Exception as exc:
        return {**_empty_result(), "error": str(exc)}

def _ensure_initialized() -> None:
    global _vault
    if _vault is None:
        initialize()

def _quick_evolve(data: bytes) -> Tuple[Optional[Node], float, float]:
    seed_val = int(hashlib.sha256(data[:64]).hexdigest()[:8], 16)
    ev = AEEvolver(data=data, seed=seed_val)
    tree, fit = ev.run(pop=20, gens=10)
    return tree, fit, ev.best_lossless

def _empty_result() -> dict:
    return {
        "fitness": 0.0, "lossless": 0.0, "nodes": 0, "depth": 0,
        "has_anchor": False, "evolved": False, "signature": "",
        "selected_seed": {}, "vault": {}, "commit_allowed": False,
    }

def _entry_payload(entry: Any) -> Optional[Dict[str, Any]]:
    if entry is None: return None
    return {
        "signature": getattr(entry, "sig", ""),
        "fitness": round(float(getattr(entry, "fitness", 0.0)), 6),
        "lossless": round(float(getattr(entry, "lossless", 0.0)), 6),
        "nodes": int(getattr(entry, "nodes", 0)),
    }

def _vault_status_locked() -> Dict[str, Any]:
    if _vault is None: return {}
    entries = list(_vault._entries.values())
    return {
        "root": str(_vault.root),
        "total_entries": len(entries),
        "main_entries": sum(1 for entry in entries if entry.bucket == "main"),
    }
