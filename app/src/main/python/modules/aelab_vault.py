from __future__ import annotations
import logging
logger = logging.getLogger(__name__)

import csv
import hashlib
import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, List, Dict

from .aelab_motor import (
    Node, node_copy, node_size, node_depth,
    has_anchor, first_anchor, anchor_mask_for,
    N_SEQUENCE, N_BRIDGE, N_XOR, N_INTERFERE,
    N_LSHIFT, N_RSHIFT, N_ZETA, N_LIOUVILLE,
    N_GF_MUL, N_HAMMING, N_ZIPF, N_LOG,
    N_CONST, NF_ANCHOR,
)

# --- Baum-Signatur & Serialisierung ---

def tree_signature(n: Optional[Node]) -> str:
    def serialize(nd):
        if nd is None: return None
        return {"t": nd.t, "iv": nd.iv, "v": round(nd.v, 12), "f": nd.f,
                "a": serialize(nd.a), "b": serialize(nd.b), "c": serialize(nd.c)}
    raw = json.dumps(serialize(n), sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(raw.encode()).hexdigest()

_DNA_HEADER = "# AETHER.DNA v1"

def tree_to_dna(n: Optional[Node]) -> str:
    if n is None: return _DNA_HEADER + "\n# nodes:0\n"
    order: list[Node] = []
    idx_of: dict[int, int] = {}
    def visit(nd):
        if nd is None: return -1
        if id(nd) in idx_of: return idx_of[id(nd)]
        i = len(order)
        idx_of[id(nd)] = i
        order.append(nd)
        nd._dna_a, nd._dna_b, nd._dna_c = visit(nd.a), visit(nd.b), visit(nd.c)
        return i
    visit(n)
    lines = [_DNA_HEADER, f"# nodes:{len(order)}"]
    for i, nd in enumerate(order):
        lines.append(f"{i} t={nd.t} iv={nd.iv} v={nd.v:.12f} f={nd.f} a={nd._dna_a} b={nd._dna_b} c={nd._dna_c}")
        del nd._dna_a, nd._dna_b, nd._dna_c
    return "\n".join(lines) + "\n"

def tree_from_dna(s: str) -> Optional[Node]:
    raw = {}
    for line in s.splitlines():
        line = line.strip()
        if not line or line.startswith("#"): continue
        try:
            parts = line.split()
            idx = int(parts[0])
            kv = {p.partition("=")[0]: p.partition("=")[2] for p in parts[1:]}
            raw[idx] = (int(kv["t"]), int(kv["iv"]), float(kv["v"]), int(kv["f"]), int(kv["a"]), int(kv["b"]), int(kv["c"]))
        except: continue
    if not raw: return None
    cache = {}
    def build(idx):
        if idx < 0 or idx not in raw: return None
        if idx in cache: return cache[idx]
        t, iv, v, f, ai, bi, ci = raw[idx]
        nd = Node(t, iv, v, f)
        cache[idx] = nd
        nd.a, nd.b, nd.c = build(ai), build(bi), build(ci)
        return nd
    return build(0)

# --- Metrik-Extraktion ---

def extract_metrics(tree: Optional[Node], fitness: float, lossless: float) -> dict:
    if tree is None: return {}
    sz, dp = node_size(tree), node_depth(tree)
    stability = min(1.0, lossless * 0.70 + min(node_size(tree), 4)/4.0 * 0.30)
    return {
        "nodes": sz, "depth": dp, "stability": round(stability, 6),
        "fitness": round(fitness, 8), "lossless": round(lossless, 8)
    }

@dataclass
class VaultEntry:
    sig: str = ""; label: str = ""; bucket: str = "main"
    fitness: float = 0.0; lossless: float = 0.0
    nodes: int = 0; depth: int = 0; utility_score: float = 0.0
    active: bool = True; access_seq: int = 0

    def adjusted_score(self, total_ticks: int) -> float:
        return self.utility_score + (self.access_seq / max(1, total_ticks)) * 0.1

class AEVault:
    def __init__(self, root: str = "vault"):
        self.root = Path(root)
        self._entries: Dict[str, VaultEntry] = {}
        self._trees: Dict[str, Node] = {}
        self._tick = 0
        for d in ["main", "sub_vault/inactive"]: (self.root / d).mkdir(parents=True, exist_ok=True)

    def store(self, tree: Node, fitness: float, lossless: float) -> VaultEntry:
        sig = tree_signature(tree)
        e = VaultEntry(sig=sig, fitness=fitness, lossless=lossless, utility_score=0.5, access_seq=self._tick)
        self._entries[sig], self._trees[sig] = e, node_copy(tree)
        (self.root / "main" / f"{sig[:16]}.dna").write_text(tree_to_dna(tree))
        return e

    def best_seed(self) -> Optional[Node]:
        if not self._entries: return None
        best_sig = max(self._entries.values(), key=lambda e: e.adjusted_score(self._tick)).sig
        return node_copy(self._trees.get(best_sig))
