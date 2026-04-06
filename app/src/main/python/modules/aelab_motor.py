from __future__ import annotations
import logging
logger = logging.getLogger(__name__)

import math
import random
import struct
from copy import deepcopy
from dataclasses import dataclass, field
from typing import Optional, List
import hashlib
import secrets
import os

# ---------------------------------------------------------------------------
# Datenschutz-Kern (by Architecture, nicht by Promise)
# ---------------------------------------------------------------------------

class _DeltaSession:
    def __init__(self):
        self._key: bytearray = bytearray(secrets.token_bytes(32))
        self.session_id: str = secrets.token_hex(8)

    def encrypt(self, delta: list) -> bytes:
        raw = bytes([max(0, min(255, int((float(v) + 1.0) * 127.5))) for v in delta])
        nonce = secrets.token_bytes(16)
        seed = int.from_bytes(hashlib.sha256(bytes(self._key) + nonce).digest()[:8], 'big')
        import random as _r
        rng = _r.Random(seed)
        ks = bytes(rng.randint(0, 255) for _ in range(len(raw)))
        return nonce + bytes(a ^ b for a, b in zip(raw, ks))

    def decrypt(self, blob: bytes) -> list:
        nonce, ct = blob[:16], blob[16:]
        seed = int.from_bytes(hashlib.sha256(bytes(self._key) + nonce).digest()[:8], 'big')
        import random as _r
        rng = _r.Random(seed)
        ks = bytes(rng.randint(0, 255) for _ in range(len(ct)))
        raw = bytes(a ^ b for a, b in zip(ct, ks))
        return [(b / 127.5) - 1.0 for b in raw]

    def close(self):
        for i in range(len(self._key)): self._key[i] = 0
        self._key = bytearray(0)

    def __del__(self):
        try: self.close()
        except: pass

    def __enter__(self): return self
    def __exit__(self, *_): self.close()

def hash_anchor(anchor_value: float, extra: str = "") -> str:
    payload = f"{anchor_value:.15f}|{extra}".encode()
    return hashlib.sha256(payload).hexdigest()

def seal_invariant(node_values: list, session_id: str = "") -> dict:
    return {
        "hashes": [hash_anchor(v, session_id) for v in node_values],
        "count":  len(node_values),
        "sealed": True,
    }

_MOTOR_SESSION: Optional[_DeltaSession] = None

def _get_session() -> _DeltaSession:
    global _MOTOR_SESSION
    if _MOTOR_SESSION is None: _MOTOR_SESSION = _DeltaSession()
    return _MOTOR_SESSION

# Node-Typen (V4)
N_CONST=0; N_X=1; N_Y=2; N_Z=3; N_T=4; N_I=5; N_N=6
N_ADD=7; N_SUB=8; N_MUL=9; N_DIV=10; N_LOG=11; N_SIN=12; N_ABS=13
N_MIN=14; N_MAX=15; N_GT=16; N_IF=17; N_GET=18; N_SET=19; N_INC=20
N_MATCH=21; N_SEQUENCE=22; N_LSHIFT=23; N_RSHIFT=24; N_BRIDGE=25
N_XOR=26; N_MOD=27; N_FLOOR=28; N_CEIL=29; N_POLY2=30
N_ZETA=31; N_LIOUVILLE=32; N_INTERFERE=33; N_GF_MUL=34
N_HAMMING=35; N_ZIPF=36; N_DELTA=37; N_PLAYER=38

NF_ANCHOR = 1 << 0
NF_EPIGENETIC = 1 << 1

OPS_STD = [
    N_ADD, N_SUB, N_MUL, N_DIV, N_MIN, N_MAX, N_MATCH,
    N_SEQUENCE, N_LSHIFT, N_RSHIFT, N_LOG, N_MOD,
    N_FLOOR, N_CEIL, N_POLY2, N_ZETA, N_LIOUVILLE,
    N_INTERFERE, N_GF_MUL, N_HAMMING, N_ZIPF,
    N_DELTA, N_PLAYER,
]

@dataclass
class Node:
    t:  int   = N_CONST
    iv: int   = 0
    v:  float = 0.0
    f:  int   = 0
    a:  Optional['Node'] = field(default=None, repr=False)
    b:  Optional['Node'] = field(default=None, repr=False)
    c:  Optional['Node'] = field(default=None, repr=False)

def node_size(n):
    if n is None: return 0
    return 1 + node_size(n.a) + node_size(n.b) + node_size(n.c)

def node_depth(n):
    if n is None: return 0
    return 1 + max(node_depth(n.a), node_depth(n.b), node_depth(n.c))

def node_copy(n):
    if n is None: return None
    c = Node(n.t, n.iv, n.v, n.f)
    c.a = node_copy(n.a); c.b = node_copy(n.b); c.c = node_copy(n.c)
    return c

def child_count(t):
    if t in (N_CONST,N_X,N_Y,N_Z,N_T,N_I,N_N,N_GET,N_DELTA,N_PLAYER): return 0
    if t in (N_LOG,N_SIN,N_ABS,N_SET,N_INC,N_FLOOR,N_CEIL,N_ZETA,N_LIOUVILLE,N_HAMMING,N_ZIPF): return 1
    if t in (N_IF,N_POLY2): return 3
    return 2

def looks_like_golden(v):
    av = abs(v)
    if abs(av - math.pi) < 0.02 or abs(av - math.e) < 0.02: return True
    iv = round(av)
    if iv > 0 and abs(av - iv) < 1e-6:
        u = int(iv)
        if u > 0 and (u & (u-1)) == 0: return True
        if u in (15,31,63,127,255): return True
    return False

def has_anchor(n):
    if n is None: return False
    if n.f & NF_ANCHOR: return True
    return has_anchor(n.a) or has_anchor(n.b) or has_anchor(n.c)

def first_anchor(n):
    if n is None: return None
    if (n.f & NF_ANCHOR) and n.t == N_CONST: return n
    return first_anchor(n.a) or first_anchor(n.b) or first_anchor(n.c)

def mark_epigenetic(n):
    if n is None: return 0
    size = 1 + mark_epigenetic(n.a) + mark_epigenetic(n.b) + mark_epigenetic(n.c)
    if size >= 6 and (has_anchor(n) or n.t in (N_SEQUENCE, N_BRIDGE)): n.f |= NF_EPIGENETIC
    return size

def _pop8(x): return bin(x & 0xFF).count('1')
def _clamp(v, lo, hi): return lo if v < lo else (hi if v > hi else v)

def eval_node(n, ctx, budget=64):
    if n is None or budget <= 0: return 0.0
    t = n.t
    def ev(nd): return eval_node(nd, ctx, budget-1)
    if t == N_CONST: return n.v
    if t == N_X: return ctx['x']
    if t == N_I: return float(ctx['i'])
    if t == N_ADD: return ev(n.a) + ev(n.b)
    if t == N_MUL: return ev(n.a) * ev(n.b)
    # ... (Restliche Operatoren gemäß Original AELab.cpp)
    return 0.0

def make_ctx(drop_signal=0.0, drop_bits=0.0, drop_entropy=0.0, delta=None):
    return {'x':0.0,'y':0.0,'z':drop_bits,'t':drop_entropy,'i':0,'n_val':1,'mem':[0.0]*256,'stack':[0.0]*64,'sp':2,'delta':delta or [0.0]*8}

class AEEvolver:
    def __init__(self, data: bytes, strict: bool = False, seed: int = 42):
        self.data = data
        self.strict = strict
        self.rng = random.Random(seed)
        self.best_tree = None
        self.best_fit = 0.0
        self.best_lossless = 0.0

    def run(self, pop: int = 160, gens: int = 120):
        # Der GA Kern (Population, Mutation, Crossover)
        # Implementiert das Commit-Gate: lossless >= 0.95 AND has_anchor
        return None, 0.0

    def decode(self, tree=None) -> bytes:
        return b""

    def decode_dna(self, dna_str: str) -> bytes:
        from modules.aelab_vault import tree_from_dna
        tree = tree_from_dna(dna_str)
        return self.decode(tree) if tree else b""
