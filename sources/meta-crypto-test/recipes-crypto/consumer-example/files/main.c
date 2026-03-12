/*
 * mlkem-native integration test
 *
 * Based on examples/basic_deterministic from the mlkem-native repository.
 * Tests a deterministic ML-KEM roundtrip (keygen, encaps, decaps) and
 * verifies the shared secret against known-answer values.
 *
 * MLK_CONFIG_PARAMETER_SET must be set via -D on the compiler command line
 * (e.g., -DMLK_CONFIG_PARAMETER_SET=768).  If not set, the installed
 * mlkem_native_config.h defaults to 768.
 *
 * SPDX-License-Identifier: Apache-2.0 OR ISC OR MIT
 */

#include <stdio.h>
#include <string.h>
#include <stdint.h>

/*
 * Include the installed mlkem-native public API header.
 *
 * The installed config file provides MLK_CONFIG_PARAMETER_SET (overridable
 * via -D) and MLK_CONFIG_NAMESPACE_PREFIX.  The SUPERCOP aliases
 * (crypto_kem_*, CRYPTO_*) are enabled by default.
 */
#include <mlkem-native/mlkem_native.h>

#define CHECK(cond, msg)                              \
  do {                                                \
    if (!(cond)) {                                    \
      fprintf(stderr, "FAIL: %s (%s:%d)\n",           \
              (msg), __FILE__, __LINE__);              \
      return 1;                                       \
    }                                                 \
  } while (0)

/* Known-answer expected shared secrets for deterministic entropy:
 *   keypair coins: all-zero  (2 * MLKEM_SYMBYTES bytes)
 *   encaps  coins: {1, 0, 0, ...}  (MLKEM_SYMBYTES bytes)
 *
 * These match the values from examples/basic_deterministic/main.c in the
 * mlkem-native repository.
 */
#if MLK_CONFIG_PARAMETER_SET == 512
static const uint8_t expected_key[] = {
    0x5f, 0x5f, 0x8c, 0xf5, 0x7c, 0x34, 0xd4, 0x68,
    0x06, 0xa2, 0xe9, 0xc9, 0x28, 0xba, 0x10, 0x5a,
    0x46, 0xf2, 0x67, 0x1a, 0xc7, 0x81, 0xdf, 0xf1,
    0x4a, 0xbb, 0x27, 0xea, 0x46, 0x06, 0x46, 0x3c};
#elif MLK_CONFIG_PARAMETER_SET == 768
static const uint8_t expected_key[] = {
    0x85, 0x21, 0xab, 0xc8, 0x14, 0xc7, 0x67, 0x70,
    0x4f, 0xa6, 0x25, 0xd9, 0x35, 0x95, 0xd0, 0x03,
    0x79, 0xa8, 0xb3, 0x70, 0x35, 0x2c, 0xa4, 0xba,
    0xb3, 0xa6, 0x82, 0x46, 0x63, 0x0d, 0xb0, 0x8b};
#elif MLK_CONFIG_PARAMETER_SET == 1024
static const uint8_t expected_key[] = {
    0x30, 0x4d, 0xbe, 0x54, 0xd6, 0x6f, 0x80, 0x66,
    0xc6, 0xa8, 0x1c, 0x6b, 0x36, 0xc4, 0x48, 0x9b,
    0xf9, 0xe6, 0x05, 0x79, 0x83, 0x3c, 0x4e, 0xdc,
    0x8a, 0xc7, 0x92, 0xe5, 0x73, 0x0d, 0xdd, 0x85};
#else
#error "Unsupported MLK_CONFIG_PARAMETER_SET value"
#endif

int main(void)
{
  uint8_t pk[CRYPTO_PUBLICKEYBYTES];
  uint8_t sk[CRYPTO_SECRETKEYBYTES];
  uint8_t ct[CRYPTO_CIPHERTEXTBYTES];
  uint8_t key_a[CRYPTO_BYTES];
  uint8_t key_b[CRYPTO_BYTES];

  /* Deterministic entropy — matches examples/basic_deterministic */
  uint8_t coins_keypair[2 * MLKEM_SYMBYTES] = {0};
  uint8_t coins_enc[MLKEM_SYMBYTES] = {1};

  size_t i;

  printf("ML-KEM-%d deterministic roundtrip test\n",
         MLK_CONFIG_PARAMETER_SET);

  /* KeyGen */
  printf("  keypair_derand ... ");
  CHECK(crypto_kem_keypair_derand(pk, sk, coins_keypair) == 0,
        "keypair_derand failed");
  printf("OK\n");

  /* Encaps */
  printf("  enc_derand     ... ");
  CHECK(crypto_kem_enc_derand(ct, key_b, pk, coins_enc) == 0,
        "enc_derand failed");
  printf("OK\n");

  /* Decaps */
  printf("  dec            ... ");
  CHECK(crypto_kem_dec(key_a, ct, sk) == 0,
        "dec failed");
  printf("OK\n");

  /* Shared secret must match */
  printf("  secret match   ... ");
  CHECK(memcmp(key_a, key_b, CRYPTO_BYTES) == 0,
        "shared secrets differ");
  printf("OK\n");

  /* Print shared secret */
  printf("  shared secret: ");
  for (i = 0; i < CRYPTO_BYTES; i++)
    printf("%02x", key_a[i]);
  printf("\n");

  /* KAT check */
  printf("  KAT check      ... ");
  CHECK(memcmp(key_a, expected_key, CRYPTO_BYTES) == 0,
        "shared secret does not match expected KAT value");
  printf("OK\n");

  printf("ML-KEM-%d: ALL TESTS PASSED\n", MLK_CONFIG_PARAMETER_SET);
  return 0;
}

