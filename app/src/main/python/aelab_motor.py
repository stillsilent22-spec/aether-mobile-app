# AELAB MOTOR — AETHER.DNA v1 & AEF ENGINE
# Author: Kevin Hannemann | Version: April 2026

import random
import math
import hashlib
import numpy as np

class AELabNode:
    def __init__(self, t, iv=0, v=0.0, f=0, a=-1, b=-1, c=-1):
        self.t = t    # Node-Typ (Op-Code)
        self.iv = iv  # Integer Value
        self.v = v    # Float Value
        self.f = f    # Flags (4=IS_ANCHOR)
        self.a = a    # Child A Index
        self.b = b    # Child B Index
        self.c = c    # Child C Index

    def execute(self, x, nodes):
        """Pre-Order Ausführung basierend auf AETHER.DNA v1."""
        if self.t == 2: return self.v # N_CONST
        elif self.t == 1: return x    # N_X
        elif self.t == 7: # N_DIV
            va = nodes[self.a].execute(x, nodes) if self.a != -1 else 1.0
            vb = nodes[self.b].execute(x, nodes) if self.b != -1 else 1.0
            return va / vb if vb != 0 else 0.0
        return self.v

class AELabMotor:
    def __init__(self, tier="MID"):
        self.vault = {} # SHA256 -> DNA_STR
        config = {"LOW": (10, 5), "MID": (30, 12), "HIGH": (60, 25)}
        self.pop_size, self.generations = config.get(tier, config["MID"])

    def to_dna_v1(self, nodes):
        """Serialisiert Baum in AETHER.DNA v1 (deterministisch)."""
        header = f"# AETHER.DNA v1\n# nodes:{len(nodes)} t=7 iv=0 v=0.0 f=0 a=1 b=2 c=-1\n"
        body = "\n".join([f"{i} t={n.t} iv={n.iv} v={n.v:.12f} f={n.f} a={n.a} b={n.b} c={n.c}" for i, n in enumerate(nodes)])
        return header + body

    def fitness_drop(self, tree_nodes, target_stream):
        """AEF Trust-Metriken: Noether, Einstein, Bit-Error."""
        target = np.frombuffer(target_stream, dtype=np.uint8).astype(np.float32)
        pred = np.array([tree_nodes[0].execute(i, tree_nodes) for i in range(len(target))], dtype=np.float32)

        # 1. Bit-Error (pop8 XOR Simulation)
        bit_score = 1.0 - (np.mean(np.abs(pred.astype(np.int32) ^ target.astype(np.int32))) / 255.0)

        # 2. Einstein-Penalty & Hardware-Last
        size = len(tree_nodes)
        einstein = (size**2) / 10000.0
        hw_penalty = (1 + size * 0.01)

        # 3. Knowledge-Ratio (1 - delta)
        delta_ratio = np.mean(np.abs(pred - target)) / 255.0
        knowledge_ratio = 1.0 - delta_ratio

        # AEF Trust Score Gewichtung (Noether, Benford simuliert)
        noether = random.uniform(0.3, 0.9)
        benford = random.uniform(0.4, 0.8)
        coherence = (benford * 0.35) + (knowledge_ratio * 0.40) + 0.125

        trust = (noether * 0.18) + (knowledge_ratio * 0.18) + (coherence * 0.24) + (benford * 0.10)
        trust /= (hw_penalty + einstein)

        return trust, knowledge_ratio

    def evolve(self, target_stream):
        # Einfache Evolution Simulation für das DNA v1 Format
        best_nodes = [
            AELabNode(t=7, a=1, b=2),
            AELabNode(t=2, v=3.141592653589, f=4),
            AELabNode(t=2, v=1.0)
        ]

        trust, k_ratio = self.fitness_drop(best_nodes, target_stream)
        dna_str = self.to_dna_v1(best_nodes)
        dna_hash = hashlib.sha256(dna_str.encode()).hexdigest()

        if trust > 0.4:
            self.vault[dna_hash] = dna_str
            return dna_hash, trust, k_ratio
        return None, trust, k_ratio
