# mlkem-native Yocto Integration

This repository provides a [Yocto/OpenEmbedded](https://www.yoctoproject.org/) integration layer (`meta-crypto-test`) for building [mlkem-native](https://github.com/pq-code-package/mlkem-native) — a portable C implementation of **ML-KEM (FIPS 203)**, the NIST post-quantum Key Encapsulation Mechanism standard. It targets multiple architectures via [kas](https://kas.readthedocs.io/) multiconfig and uses [cqfd](https://github.com/savoirfairelinux/cqfd) to provide a reproducible containerised build environment.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [What is cqfd?](#what-is-cqfd)
3. [What is kas?](#what-is-kas)
4. [Getting Started](#getting-started)
5. [The mlkem-buildsys Recipe](#the-mlkem-buildsys-recipe)
6. [Consumer Recipe Example](#consumer-recipe-example)
7. [Running the QEMU Image](#running-the-qemu-image)
8. [Generating the SDK](#generating-the-sdk)
9. [Using the SDK for Development](#using-the-sdk-for-development)

---

## Prerequisites

- Docker (or Podman) installed and running
- `cqfd` installed (see below)
- Git

---

## What is cqfd?

[**cqfd**](https://github.com/savoirfairelinux/cqfd) (_"Ce qu'il fallait dockerizer"_) is a lightweight tool that wraps your build commands inside a Docker container. 

The container image is defined in [`.cqfd/docker/Dockerfile`](.cqfd/docker/Dockerfile), which installs all Yocto-required host packages (from the [Yocto Quick Build guide](https://docs.yoctoproject.org/brief-yoctoprojectqs/index.html)) plus kas and other utilities. The [`.cqfdrc`](.cqfdrc) file at the repository root configures cqfd's default build command.

### Installing cqfd

cqfd is a single shell script. Install it from its GitHub repository:

```bash
git clone https://github.com/savoirfairelinux/cqfd.git
cd cqfd
sudo make install
```

Alternatively, if you're on a Debian/Ubuntu-based system, you can install via the provided `.deb` packaging — see the [cqfd README](https://github.com/savoirfairelinux/cqfd#installation) for details.

After installation, verify with:

```bash
cqfd version
```

---

## What is kas?

[**kas**](https://kas.readthedocs.io/) is a setup tool for Yocto/OpenEmbedded projects. It replaces the manual process of cloning layers, running `oe-init-build-env`, editing `local.conf` / `bblayers.conf`, and managing multiple build configurations.

A single YAML file ([`.config.yaml`](.config.yaml)) declares:

- **Which layers** to fetch (and from which branches/commits)
- **Machine and distro** settings
- **Build targets** — including multiconfig targets for multiple architectures
- **`local.conf` overrides** — image features, package additions, etc.

This repository uses kas multiconfig to build images for three architectures from one command:

| Multiconfig | Machine         | Architecture |
|-------------|-----------------|--------------|
| `aarch64`   | `qemuarm64`    | AArch64      |
| `x86`       | `qemux86-64`   | x86-64       |
| `riscv64`   | `qemuriscv64`  | RISC-V 64    |

---

## Getting Started

### 1. Build the container image

Before the first build, initialise the cqfd Docker container:

```bash
cqfd init
```

This builds the Docker image from `.cqfd/docker/Dockerfile`. It only needs to be done once (or after modifying the Dockerfile).

### 2. Fetch layers and run the build

Run the full build (fetches all source layers via kas and builds the multiconfig targets):

```bash
cqfd run kas build .config.yaml
```

This single command will:

1. Enter the cqfd container
2. Invoke kas, which clones/updates all layer repositories (`openembedded-core`, `meta-yocto`, etc.)
3. Set up the build environment (`local.conf`, `bblayers.conf`)
4. Build all multiconfig targets defined in `.config.yaml`

Subsequent builds (after the initial fetch) can simply use:

```bash
cqfd
```

This runs the default build command configured in `.cqfdrc` (which is `kas build`). Note that by default the configuration will build for all three target architectures -- the resulting build will take a long time to complete. One can comment out some of these lines in the config.yaml in order to make the build process shorter or only test for one architecture.

### Building a single architecture

To build only a specific multiconfig target:

```bash
cqfd run kas shell 
bitbake multiconfig:aarch64:core-image-minimal'
```

---

## The mlkem-buildsys Recipe

The core recipe is located at:

```
sources/meta-crypto-test/recipes-crypto/mlkem-native/mlkem-buildsys.bb
```

It builds mlkem-native using the **upstream Makefile** build system. The recipe:

- Fetches the mlkem-native source from GitHub
- Invokes the upstream `Makefile` + `test/mk/*` infrastructure
- Handles **cross-compilation** by overriding auto.mk's host feature detection (`MK_HOST_SUPPORTS_*`) with values derived from Yocto's `TUNE_FEATURES`
- Installs **headers** and **static archives** into the sysroot so that consuming recipes can link against them

### Configurable Variables

The recipe exposes several knobs that consumers can override via `.bbappend` files, `local.conf`, or machine configuration:

| Variable                  | Default | Description                                                  |
|---------------------------|---------|--------------------------------------------------------------|
| `MLKEM_PARAMETER_SET`     | `768`   | Security level to build: `512`, `768`, or `1024`             |
| `MLKEM_MULTILEVEL`        | `0`     | Set to `1` to build all three levels + a combined archive    |
| `MLKEM_NATIVE_BACKEND`    | `1`     | `1` = enable platform-optimised assembly; `0` = pure C only  |
| `MLKEM_NAMESPACE_PREFIX`  | `""`    | Override symbol namespace prefix (empty = upstream defaults)  |
| `MLKEM_KEYGEN_PCT`        | `0`     | `1` = enable FIPS 140-3 Pairwise Consistency Test on keygen  |
| `MLKEM_SERIAL_FIPS202`    | `0`     | `1` = disable batched Keccak (useful for HW accelerators)    |
| `MLK_CONFIG_NO_RANDOMIZED_API` | `0` | `1` = remove randomised API (deterministic-only build)      |

### Build Artefacts

The recipe produces static libraries and headers, installed into the sysroot:

- **Headers:** `${includedir}/mlkem-native/mlkem_native.h`, `mlkem_native_config.h`
- **Libraries:** `${libdir}/libmlkem512.a`, `libmlkem768.a`, `libmlkem1024.a`, `libmlkem.a` (depending on configuration)

---

## Consumer Recipe Example

The repository includes a complete example of how a downstream project would consume mlkem-native. It lives under:

```
sources/meta-crypto-test/recipes-crypto/consumer-example/
├── mlkem-buildsys.bbappend       # Overrides mlkem-buildsys configuration
├── mlkem-consumer-test.bb        # Test recipe that links against mlkem-native
└── files/
    └── main.c                    # Deterministic KEM roundtrip test program
```

### The `.bbappend` File

[`mlkem-buildsys.bbappend`](sources/meta-crypto-test/recipes-crypto/consumer-example/mlkem-buildsys.bbappend) customises the mlkem-buildsys recipe for this particular consumer's needs:

```bitbake
MLKEM_MULTILEVEL = "1"
MLK_CONFIG_NO_RANDOMIZED_API = "1"
```

This tells the library build to:
- Produce archives for **all three** parameter sets (512, 768, 1024) via `MLKEM_MULTILEVEL = "1"`
- Disable the randomised API (`MLK_CONFIG_NO_RANDOMIZED_API = "1"`) since the test only uses the deterministic `_derand` entry points

### The Consumer Test Recipe

[`mlkem-consumer-test.bb`](sources/meta-crypto-test/recipes-crypto/consumer-example/mlkem-consumer-test.bb) declares `DEPENDS = "mlkem-buildsys"` to pull in the headers and static archives at build time. It compiles three test binaries — one per ML-KEM security level — each performing a deterministic KEM roundtrip (keygen → encaps → decaps) and verifying the shared secret against known-answer values.

### The Consumer Mentality

The intended workflow for projects that want to use mlkem-native in their Yocto images is:

1. **Add `meta-security` (once upstreamed) to your layer stack** (or copy the `mlkem-buildsys.bb` recipe into your own layer).

2. **Create a `.bbappend` for `mlkem-buildsys`** in your own recipe directory to configure the library for your needs — choose your parameter set, enable/disable the native backend, set namespace prefixes, etc.

3. **Write your own application recipe** that declares `DEPENDS = "mlkem-buildsys"` and compiles/links against the installed headers and static archives:
   ```c
   #include <mlkem-native/mlkem_native.h>
   ```
   ```bitbake
   # your-app.bb
   DEPENDS = "mlkem-buildsys"
   # Link with: ${LDFLAGS} -lmlkem768
   ```

4. **Add your recipe to `IMAGE_INSTALL`** in your kas configuration or `local.conf`.

The mlkem-buildsys recipe is a **build-time-only dependency** — it installs nothing to the target rootfs. Only your consumer recipe's compiled binaries end up on the target image.

---

## Running the QEMU Image

After a successful build, you can boot the generated QEMU image to test your mlkem-native binaries interactively.

### 1. Enter the kas shell

```bash
cqfd run kas shell 
```

This drops you into a configured BitBake environment inside the cqfd container.

### 2. Launch QEMU

From within the kas shell, start the emulator for your target architecture:

```bash
# AArch64
runqemu multiconfig:aarch64:core-image-minimal nographic slirp snapshot

# x86-64
runqemu multiconfig:x86:core-image-minimal nographic slirp snapshot
```

> **Note:** The `snapshot` option runs with a temporary copy of the root filesystem image, avoiding modifications to the build artefact. `nographic` disables the graphical display (serial console only). 

### 3. Log in and run the test

Once the QEMU machine boots to a login prompt, log in as `root` (no password required — `debug-tweaks` is enabled in the build configuration).

Then run the test binaries:

```bash
mlkem-test-512
mlkem-test-768
mlkem-test-1024
```

Each should print:

```
ML-KEM-<level> deterministic roundtrip test
  keypair_derand ... OK
  enc_derand     ... OK
  dec            ... OK
  secret match   ... OK
  shared secret: <hex>
  KAT check      ... OK
ML-KEM-<level>: ALL TESTS PASSED
```

### 4. Exit QEMU

Press `Ctrl-A` then `X` to terminate the QEMU session.

---

## Generating the SDK

The Yocto SDK bundles a cross-toolchain, sysroot, and all build-time dependencies into a self-extracting installer. This allows developers to compile and link against mlkem-native **outside** of the Yocto build system.

### Including mlkem-native in the SDK

By default, `populate_sdk` only packages `-dev` and `-staticdev` files for recipes whose **runtime packages are installed in the image**. Since `mlkem-buildsys` is a build-time-only recipe (its main package is empty), its headers and static libraries would **not** be included automatically.

The `.config.yaml` in this repository already handles this by adding:

```yaml
local_conf_header:
  sdk: |
    TOOLCHAIN_TARGET_TASK:append = " mlkem-buildsys-dev mlkem-buildsys-staticdev"
```

This ensures the SDK sysroot contains:

| Package                    | Contents                                                                 |
|----------------------------|--------------------------------------------------------------------------|
| `mlkem-buildsys-dev`       | `<sysroot>/usr/include/mlkem-native/mlkem_native.h`, `mlkem_native_config.h` |
| `mlkem-buildsys-staticdev` | `<sysroot>/usr/lib/libmlkem512.a`, `libmlkem768.a`, `libmlkem1024.a`, `libmlkem.a` |

If you are adapting this for your own project, remember to add this `TOOLCHAIN_TARGET_TASK:append` line — without it the SDK will have the cross-compiler but no mlkem-native artefacts to link against.

### 1. Enter the kas shell

```bash
cqfd run kas shell 
```

### 2. Build the SDK

From within the kas shell, generate the SDK for your target architecture:

```bash
# AArch64 SDK
bitbake multiconfig:aarch64:core-image-minimal -c populate_sdk

# x86-64 SDK
bitbake multiconfig:x86:core-image-minimal -c populate_sdk

# RISC-V 64 SDK
bitbake multiconfig:riscv64:core-image-minimal -c populate_sdk
```

The resulting SDK installer is a self-extracting shell script located in:

```
build/tmp/deploy/sdk/poky-glibc-*-core-image-minimal-*-toolchain-*.sh
```

---

## Using the SDK for Development

The SDK allows you to develop and cross-compile applications that use mlkem-native without needing the full Yocto build environment.

### 1. Install the SDK

Run the self-extracting installer (default installs to `/opt/poky`):

```bash
./poky-glibc-x86_64-core-image-minimal-cortexa57-toolchain-*.sh
```

Accept the licence and choose an installation directory when prompted.

### 2. Source the environment

Before each development session, source the SDK environment setup script:

```bash
source /opt/poky/<version>/environment-setup-<target>
```

This sets `CC`, `CFLAGS`, `LDFLAGS`, `PKG_CONFIG_PATH`, and other variables to point at the cross-compiler and sysroot.

### 3. Compile your application

After sourcing the environment, the mlkem-native headers and static libraries are available in the SDK sysroot at:

```
$SDKTARGETSYSROOT/usr/include/mlkem-native/   # Headers
$SDKTARGETSYSROOT/usr/lib/libmlkem512.a        # Static libraries
$SDKTARGETSYSROOT/usr/lib/libmlkem768.a
$SDKTARGETSYSROOT/usr/lib/libmlkem1024.a
$SDKTARGETSYSROOT/usr/lib/libmlkem.a
```

The environment script sets `CC`, `CFLAGS`, and `LDFLAGS` to point at the sysroot, so standard compilation just works:

```bash
# Example: compile a program that uses ML-KEM-768
$CC $CFLAGS -DMLK_CONFIG_PARAMETER_SET=768 \
    -o my_app my_app.c \
    $LDFLAGS -lmlkem768
```

The headers are included via their standard path:

```c
#include <mlkem-native/mlkem_native.h>
```

> **Tip:** You can verify the libraries are present with:
> ```bash
> ls $SDKTARGETSYSROOT/usr/lib/libmlkem*.a
> ```

### 4. Deploy to target

Copy the resulting binary to your target device (or QEMU image) and run it. No mlkem-native shared libraries need to be on the target — everything is statically linked.


---

## Repository Structure

```
.
├── .config.yaml                          # kas build configuration
├── .cqfdrc                               # cqfd project settings
├── .cqfd/docker/
│   └── Dockerfile                        # Container image for builds
├── conf/multiconfig/
│   ├── aarch64.conf                      # MACHINE = "qemuarm64"
│   ├── x86.conf                          # MACHINE = "qemux86-64"
│   └── riscv64.conf                      # MACHINE = "qemuriscv64"
└── sources/meta-crypto-test/
    ├── conf/layer.conf                   # Layer configuration
    └── recipes-crypto/
        ├── mlkem-native/
        │   └── mlkem-buildsys.bb         # Core mlkem-native recipe
        └── consumer-example/
            ├── mlkem-buildsys.bbappend   # Consumer config overrides
            ├── mlkem-consumer-test.bb    # Example consumer recipe
            └── files/main.c             # Deterministic KEM test
```

---

## License

The mlkem-native library is licensed under **Apache-2.0 OR ISC OR MIT** (tri-licensed). See the [mlkem-native repository](https://github.com/pq-code-package/mlkem-native) for full details.
