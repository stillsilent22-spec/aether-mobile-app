# Aether Mobile App

Android-Client fuer das **Aether**-System - dezentrale Echtzeit-Analyse und Mesh-Kommunikation.

---

## Kernsystem / Core Engine

> Das vollstaendige Aether Delta Engine Backend (Python + Rust):
>
> **[github.com/stillsilent22-spec/Aether-](https://github.com/stillsilent22-spec/Aether-)**

---

## Architektur

```text
MainActivity (Compose)
    +-- FramePipelineService   <- MediaProjection + RGBA-Sampling
          +-- AetherCascadeEngine  <- Shannon, Katz, Benford, Noether, Trust
```n
## Minimum Android

API 26 (Android 8.0 Oreo) - getestet bis API 35 (Android 15)
