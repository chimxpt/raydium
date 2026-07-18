# Native NGX shim — build instructions

This directory contains the complete source of the two native libraries that ship
inside the Raydium jar:

| shipped file (inside the jar) | built from |
|---|---|
| `natives/libmcrt_ngx.so` | `mcrt_ngx.cpp` via `build.sh` (Linux) |
| `natives/mcrt_ngx.dll` | `mcrt_ngx.cpp` via `build-win.bat` (Windows) |

They are a thin bridge that lets the mod's Java code drive **NVIDIA NGX / DLSS Ray
Reconstruction** through the Java 21 Panama FFI. They contain no NVIDIA code — they
only call into the NGX SDK.

Raydium is licensed under **LGPL-3.0**, which means you are entitled to the complete
corresponding source of everything we ship and to rebuild and relink it. That is what
this directory is for.

## What is *not* here, and why

The NVIDIA DLSS SDK (headers, `libnvsdk_ngx.a` / `nvsdk_ngx_d.lib`, and the Ray
Reconstruction model) is **not** redistributed here. It is NVIDIA's, governed by the
NVIDIA RTX SDKs License, and we have no right to hand it out. The build scripts fetch
it directly from NVIDIA's public repository instead:

```
https://github.com/NVIDIA/DLSS
```

Everything the scripts download lands in `deps/`, which is git-ignored.

## Prerequisites

Both platforms:

* **JDK 21** with `JAVA_HOME` set (the shim includes `jni.h`)
* **git** (the scripts clone the DLSS SDK and Vulkan headers)

Linux:

* `g++` with C++17 support

Windows:

* **Build Tools for Visual Studio** with the *"C++ build tools"* component.
  The script switches itself to an x64 environment — `nvsdk_ngx_d.lib` is 64-bit only,
  and building from a 32-bit prompt fails with `LNK2019` / `LNK4272`.

## Building

### Linux

```bash
bash native/build.sh
```

The script clones the DLSS SDK and Vulkan headers into `native/deps/`, compiles
`libmcrt_ngx.so`, and writes it straight into `src/main/resources/natives/` so the next
Gradle build picks it up.

Optionally, set `MC_DIR` to also drop the ~40 MB Ray Reconstruction model into your game
directory (it is loaded from `<game>/dlss/` at runtime, not from the jar):

```bash
MC_DIR=~/.minecraft bash native/build.sh
```

### Windows

The Windows script does **not** clone anything — copy the whole `native/deps/` folder
from a machine where `build.sh` has already run, then:

```bat
cd native
build-win.bat
```

It produces `mcrt_ngx.dll` next to the script. Place it in `src/main/resources/natives/`
alongside `libmcrt_ngx.so`, and copy
`deps\DLSS\lib\Windows_x86_64\rel\nvngx_dlssd.dll` into `<game>\dlss\`.

## Notes for anyone modifying this

* `/MD` is mandatory on Windows: `nvsdk_ngx_d.lib` is built against the **dynamic** CRT,
  and mixing it with the default `/MT` produces a `RuntimeLibrary mismatch` link error.
* NGX does not pull in the system libraries it uses, so they are linked explicitly:
  `advapi32` (registry), `user32`, `setupapi` and `crypt32` (device enumeration and
  driver-signature checks), `shell32`, `ole32`, `version`.
* Only the **versioned** Ray Reconstruction model file is copied into the game directory.
  NGX parses the version out of the filename and picks the newest; an extra unversioned
  copy changes nothing and merely clutters the snippet list.

## Licensing summary

* This shim and the rest of Raydium: **LGPL-3.0** (see `../LICENSE`).
* NVIDIA DLSS runtime libraries bundled in the released jar's `dlss/` directory: governed
  by the NVIDIA RTX SDKs License, aggregated but not covered by the LGPL. See `../NOTICE`.
