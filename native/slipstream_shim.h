#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

const char* ss_last_error(void);
void* ss_atlas_new(uint32_t width);
int ss_atlas_destroy(void* atlas);
int ss_atlas_build(void* atlas);
bool ss_atlas_is_built(void* atlas);

#ifdef __cplusplus
}
#endif
