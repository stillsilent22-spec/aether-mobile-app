# Aether Mobile App (Standalone Node)

This is a fully autonomous Android implementation of the Aether node.
It eliminates the need for Termux or any external runtime.

## Core Features (Ockham Style)

- **Native Identity:** Ed25519 Keypair generation on first start.
- **Embedded Engine:** Python 3.8 runtime via Chaquopy running the V4 Cascade.
- **Integrated Overlay:** Prepared for Yggdrasil P2P networking without external apps.
- **Privacy by Architecture:** All deltas remain local; only uninvertible SHA-256 anchors are synced.

## Architecture

1. **Layer 1 (Physical):** Android Hardware (CPU/Battery/Network).
2. **Layer 2 (Overlay):** Yggdrasil encrypted IPv6 stack (Port 7389).
3. **Layer 3 (Aether):** Mathematical Cascade (10 metrics) + AELab Evolution.

## Build & Deploy

1. Open in Android Studio.
2. Click **Sync Project with Gradle Files**.
3. Build -> Build APK(s).
4. Install on any device (minSdk 26).

## Contributing

Every node contributes to decentralized science and privacy-preserving data coordination.
By running this app, you become part of the Aethernet swarm.
