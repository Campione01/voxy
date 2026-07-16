# Voxy 0.2.18 NeoForge 1.21.1 Port

Unofficial NeoForge backport of Voxy 0.2.18-beta for Minecraft 1.21.1.

## Target

- Minecraft 1.21.1
- NeoForge 21.1.228 or newer
- Sodium 0.8.12 or newer
- Java 25

The source is based on the upstream Voxy 0.2.18-beta development tree and the
1.21.1 Sodium 0.8.12 integration line. Platform-specific rendering hooks were
adapted to the APIs available on Minecraft 1.21.1 and NeoForge.

## Included Port Work

- NeoForge bootstrap, metadata, access transformers, and config screen
- Sodium 0.8.12 visible-section and renderer integration
- Iris 1.21.1 matrix, viewport, depth, fog, and shader integration
- Voxy 0.2.18 bounds, rasterizer, storage, ingest, and concurrency updates
- Stable lightmap sampler and explicit mip-zero sampling
- Modded block tint handling and local fog shader helper
- Bundled storage backends and native LMDB/Zstd libraries

## Build

```text
gradlew.bat clean build
```

The output jar is written to `build/libs/`.

This branch is not an upstream-supported release. Source and bundled components
remain subject to their respective licenses, including the upstream `LICENSE`.
