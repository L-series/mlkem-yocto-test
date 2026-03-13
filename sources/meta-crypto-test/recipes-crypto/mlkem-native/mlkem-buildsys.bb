# Copyright (c) The mlkem-native project authors
# SPDX-License-Identifier: Apache-2.0 OR ISC OR MIT

SUMMARY = "mlkem-native: Portable C implementation of ML-KEM (FIPS 203)"
DESCRIPTION = "A clean, portable, extensively tested C implementation of \
ML-KEM (Module-Lattice-Based Key-Encapsulation Mechanism), the recently \
standardized NIST post-quantum KEM (FIPS 203). Includes optional optimized \
backends for AArch64 and x86-64."
HOMEPAGE = "https://github.com/pq-code-package/mlkem-native"
SECTION = "libs"

LICENSE = "Apache-2.0 & ISC & MIT"
LIC_FILES_CHKSUM = "file://LICENSE;md5=6b4bd0aa76b1f3cd276ea0f2197445e6"

PV = "1.0.0+git${SRCPV}"

SRC_URI = "git://github.com/pq-code-package/mlkem-native.git;protocol=https;branch=main"
SRCREV = "0c4d5957b495d03d9976ad0e5984a3b5e4454d11"

# ──────────────────────────────────────────────────────────────────────
# User-configurable knobs (override in local.conf or machine conf)
# ──────────────────────────────────────────────────────────────────────
#
# MLKEM_PARAMETER_SET: Which security level to build.
#   Only used when MLKEM_MULTILEVEL is "0" (the default).
#   Valid values: "512", "768", "1024".
MLKEM_PARAMETER_SET   ?= "768"
#
# MLKEM_MULTILEVEL: Build all three parameter sets (512, 768, 1024).
#   When "1", `make lib` builds all four archives (per-level + combined).
#   When "0" (default), only the MLKEM_PARAMETER_SET archive is built.
MLKEM_MULTILEVEL      ?= "0"
#
# MLKEM_NATIVE_BACKEND: Enable platform-optimized assembly backends.
#   "1" (default) — auto.mk and components.mk enable native backends
#                    for AArch64 (NEON) and x86-64 (AVX2) when the
#                    cross-compiler supports the required ISA extensions.
#   "0"           — pure C fallback on every architecture.
MLKEM_NATIVE_BACKEND  ?= "1"
#
# MLKEM_NAMESPACE_PREFIX: Override the symbol namespace prefix.
#   Empty (default) — use the upstream per-level defaults:
#       PQCP_MLKEM_NATIVE_MLKEM512, PQCP_MLKEM_NATIVE_MLKEM768,
#       PQCP_MLKEM_NATIVE_MLKEM1024.
MLKEM_NAMESPACE_PREFIX ?= ""
#
# MLKEM_KEYGEN_PCT: FIPS 140-3 Pairwise Consistency Test on key generation.
#   "1" — enable (significant performance cost to keygen).
#   "0" (default) — disable.
MLKEM_KEYGEN_PCT      ?= "0"
#
# MLKEM_SERIAL_FIPS202: Use serial (single-state) Keccak only.
#   "1" — disable batched Keccak (useful for HW accelerators with a
#          single Keccak state).  May reduce software performance.
#   "0" (default) — enable batched Keccak.
MLKEM_SERIAL_FIPS202  ?= "0"

# ── Target ISA feature overrides (cross-compilation) ──
# Override explicitly in machine/multiconfig conf if needed.
MLKEM_TARGET_SHA3 ?= "${@'1' if 'sha3' in (d.getVar('TUNE_FEATURES') or '').split() else '0'}"
MLKEM_TARGET_AVX2 ?= "${@'1' if 'avx2' in (d.getVar('TUNE_FEATURES') or '').split() else '0'}"
MLKEM_TARGET_SSE2 ?= "${@'1' if 'sse2' in (d.getVar('TUNE_FEATURES') or '').split() else '0'}"
MLKEM_TARGET_BMI2 ?= "${@'1' if 'bmi2' in (d.getVar('TUNE_FEATURES') or '').split() else '0'}"
MLK_CONFIG_NO_RANDOMIZED_API ?= "0"  

# ──────────────────────────────────────────────────────────────────────
# Build configuration
# ──────────────────────────────────────────────────────────────────────
# Build-time-only library: nothing is shipped to the target rootfs.
# Other recipes consume it via  DEPENDS += "mlkem-native"  which makes
# the headers and static archives available in the sysroot at compile
# time.  For SDK builds, -dev/-staticdev carry the artefacts.

inherit pkgconfig

B = "${S}"

# ── Extra CFLAGS for config knobs ──
# The upstream config.mk captures environment CFLAGS via:
#   CFLAGS := -Wall ... $(CFLAGS)
# so these -D defines survive into the final compilation flags.
CFLAGS += "${@'-DMLK_CONFIG_NAMESPACE_PREFIX=%s' % d.getVar('MLKEM_NAMESPACE_PREFIX') if d.getVar('MLKEM_NAMESPACE_PREFIX') else ''}"
CFLAGS += "${@'-DMLK_CONFIG_KEYGEN_PCT' if d.getVar('MLKEM_KEYGEN_PCT') == '1' else ''}"
CFLAGS += "${@'-DMLK_CONFIG_SERIAL_FIPS202_ONLY' if d.getVar('MLKEM_SERIAL_FIPS202') == '1' else ''}"
CFLAGS += "${@'-DMLK_CONFIG_NO_RANDOMIZED_API' if d.getVar('MLK_CONFIG_NO_RANDOMIZED_API') == '1' else ''}"

# ── Build feature-detection overrides for cross-compilation ──
def get_host_feature_overrides(d):
    """Return MK_HOST_SUPPORTS_* overrides so auto.mk skips /proc/cpuinfo."""
    arch = d.getVar('TARGET_ARCH')
    flags = []
    if arch == 'aarch64':
        flags.append('MK_HOST_SUPPORTS_SHA3=%s' % d.getVar('MLKEM_TARGET_SHA3'))
    elif arch == 'x86_64':
        flags.append('MK_HOST_SUPPORTS_AVX2=%s' % d.getVar('MLKEM_TARGET_AVX2'))
        flags.append('MK_HOST_SUPPORTS_SSE2=%s' % d.getVar('MLKEM_TARGET_SSE2'))
        flags.append('MK_HOST_SUPPORTS_BMI2=%s' % d.getVar('MLKEM_TARGET_BMI2'))
    return ' '.join(flags)

# ──────────────────────────────────────────────────────────────────────
# EXTRA_OEMAKE — passed on the make command line
# ──────────────────────────────────────────────────────────────────────
# Key points:
#
#   CROSS_PREFIX=""  — Yocto already exports CC/AR/CPP in the environment,
#       so we must NOT let config.mk prepend a prefix (that would double it).
#       config.mk does: CC ?= gcc ; CC := $(CROSS_PREFIX)$(CC)
#       With an empty CROSS_PREFIX the env CC survives untouched.
#
#   ARCH=<target>    — auto.mk normally runs "uname -m" when CROSS_PREFIX is
#       empty.  We override it to the target architecture so that the right
#       ISA-specific CFLAGS and source files are selected.
#
#   AUTO=1           — activates the auto-detection CFLAGS block in auto.mk
#       (e.g. -mavx2, -march=armv8.4-a+sha3).
#
#   OPT=1|0          — when 1, components.mk adds the native backend sources
#       and defines MLK_CONFIG_USE_NATIVE_BACKEND_{ARITH,FIPS202}.
#
#   MK_HOST_SUPPORTS_* — see get_host_feature_overrides() above.
#
#   BUILD_DIR=${B}   — keep build artefacts inside Yocto's build directory.
#
EXTRA_OEMAKE = " \
    ARCH=${TARGET_ARCH} \
    AUTO=1 \
    OPT=${@'1' if d.getVar('MLKEM_NATIVE_BACKEND') == '1' else '0'} \
    BUILD_DIR=${B} \
    ${@get_host_feature_overrides(d)} \
"

do_compile() {
    if [ "${MLKEM_MULTILEVEL}" = "1" ]; then
        bbnote "Building mlkem-native for all parameter sets (512, 768, 1024)"
        oe_runmake lib
    else
        bbnote "Building mlkem-native for ML-KEM-${MLKEM_PARAMETER_SET}"
        oe_runmake ${B}/libmlkem${MLKEM_PARAMETER_SET}.a
    fi

}

# ──────────────────────────────────────────────────────────────────────
# do_install: headers + static libraries into sysroot only
#
# Nothing is installed to the target rootfs.  Consuming recipes use
#     DEPENDS += "mlkem-buildsys"
# to get the headers and .a in their sysroot at build time.
# ──────────────────────────────────────────────────────────────────────
do_install() {
    # Headers
    install -d ${D}${includedir}/mlkem-native
    install -m 0644 ${S}/mlkem/mlkem_native.h          ${D}${includedir}/mlkem-native/
    install -m 0644 ${S}/mlkem/mlkem_native_config.h    ${D}${includedir}/mlkem-native/

    # Static libraries
    install -d ${D}${libdir}
    if [ "${MLKEM_MULTILEVEL}" = "1" ]; then
        # Multi-level: install all four archives
        for archive in ${B}/libmlkem*.a; do
            install -m 0644 "${archive}" ${D}${libdir}/
        done
    else
        # Single-level: install only the requested parameter set
        install -m 0644 ${B}/libmlkem${MLKEM_PARAMETER_SET}.a ${D}${libdir}/
    fi
}

# ──────────────────────────────────────────────────────────────────────
# Packaging: build-time dependency only, nothing on the target.
# ──────────────────────────────────────────────────────────────────────
ALLOW_EMPTY:${PN} = "1"
FILES:${PN} = ""
FILES:${PN}-staticdev = "${libdir}/libmlkem*.a"
FILES:${PN}-dev = "${includedir}/mlkem-native"
