# mlkem-native integration test
# =============================
#
# Builds and installs test binaries for all three ML-KEM parameter sets
# (512, 768, 1024) that exercise the deterministic KEM API provided by
# the mlkem-native recipe.  No randombytes() implementation is required.
#
# Based on examples/basic_deterministic from the mlkem-native source tree.
#
# Usage:
#   bitbake mlkem-native-test
#
# On the target:
#   mlkem-test-512   # should print "ALL TESTS PASSED"
#   mlkem-test-768
#   mlkem-test-1024
#
# SPDX-License-Identifier: Apache-2.0 OR ISC OR MIT

SUMMARY = "mlkem-native deterministic KEM integration test"
DESCRIPTION = "Test binaries exercising the mlkem-native deterministic API \
(keypair_derand, enc_derand, dec) for all three ML-KEM parameter sets \
with known-answer value verification."
HOMEPAGE = "https://github.com/pq-code-package/mlkem-native"
SECTION = "test"

LICENSE = "Apache-2.0 & ISC & MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/Apache-2.0;md5=89aea4e17d99a7cacdbeed46a0096b10"

# Pull in the static libraries and headers from the mlkem-native recipe
DEPENDS = "mlkem-buildsys"

# Must match MLKEM_PARAMETER_SET used when building mlkem-buildsys so that
# the corresponding libmlkem<N>.a exists in the sysroot.
MLKEM_PARAMETER_SET ?= "512 768 1024"

SRC_URI = "file://main.c"

S = "${UNPACKDIR}"

# No configure step
do_configure[noexec] = "1"

# ---------------------------------------------------------------------------
# Compile
# ---------------------------------------------------------------------------
#
# Build three test binaries, one per parameter set.  Each binary is compiled
# against the installed mlkem-native headers and linked against the
# corresponding per-level static archive.
#
# The installed mlkem_native_config.h defaults MLK_CONFIG_PARAMETER_SET to 768
# unless overridden.  We override via -D for each level.

do_compile () {
    cd ${S}
    for level in ${MLKEM_PARAMETER_SET}; do
        bbnote "Compiling mlkem-test-${level}"
        ${CC} ${CFLAGS} \
            -DMLK_CONFIG_PARAMETER_SET=${level} \
            -o ${B}/mlkem-test-${level} \
            ${S}/main.c \
            ${LDFLAGS} -lmlkem${level}
    done
}

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------

do_install () {
    install -d ${D}${bindir}
    for level in ${MLKEM_PARAMETER_SET}; do
        install -m 0755 ${B}/mlkem-test-${level} ${D}${bindir}/mlkem-test-${level}
    done
}

FILES:${PN} = "${bindir}/mlkem-test-*"

