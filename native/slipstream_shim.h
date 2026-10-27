#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
#define SS_NOEXCEPT noexcept
extern "C" {
#else
#define SS_NOEXCEPT
#endif

/* The returned pointer borrows the calling thread's error storage. It remains
 * valid until the next Slipstream C ABI operation on that thread, except another
 * ss_last_error call. Copy the string to retain it longer; do not free it. */
const char* ss_last_error(void) SS_NOEXCEPT;

/* Returns an owned atlas handle, or null on failure. A successful handle must
 * eventually be passed to ss_atlas_destroy exactly once. */
void* ss_atlas_new(uint32_t width) SS_NOEXCEPT;

/* Destroys an owned atlas handle and invalidates it for all further operations.
 * Returns zero on success and nonzero on failure. */
int ss_atlas_destroy(void* atlas) SS_NOEXCEPT;

/* The atlas handle is borrowed for the call and must still be alive.
 * Returns zero on success and nonzero on failure. */
int ss_atlas_build(void* atlas) SS_NOEXCEPT;

/* The atlas handle is borrowed for the call and must still be alive. A false
 * result is an error when ss_last_error is non-empty. */
bool ss_atlas_is_built(void* atlas) SS_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#undef SS_NOEXCEPT
