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

typedef struct SSKey {
  uint32_t type; /* 0 = packed codepoint/mask, 1 = UTF-8 name */
  uint32_t codepoint;
  const char* name;
} SSKey;

typedef struct SSColor {
  float r, g, b, a;
} SSColor;

typedef struct SSTransform {
  float x, y, z;
} SSTransform;

typedef struct SSLayer {
  SSKey key;
  SSColor color;
  SSTransform transform;
  float scale;
  uint32_t effect_id;
  float effect_param;
  uint32_t gradient_id;
  uint32_t pattern_id;
  float bleed;
  uint8_t draw_mode;
  uint8_t blend_mode;
} SSLayer;

typedef struct SSShape {
  uint32_t band_tex_x, band_tex_y;
  uint32_t band_max_x, band_max_y;
  float band_scale_x, band_scale_y;
  float band_offset_x, band_offset_y;
  float bearing_x, bearing_y;
  float width, height, advance;
  float origin_x, origin_y;
  uint32_t origin_type;
  float origin_data_x, origin_data_y;
} SSShape;

typedef struct SSQuad {
  float x0, y0, x1, y1;
} SSQuad;

typedef struct SSTextureDescriptor {
  const uint8_t* bytes; /* borrowed from the atlas */
  uint64_t byte_count;
  uint32_t width, height, depth;
  uint32_t format;
} SSTextureDescriptor;

typedef struct SSMeshDescriptor {
  const float* vertices; /* borrowed from the mesh handle */
  const uint16_t* indices; /* borrowed from the mesh handle */
  uint64_t vertex_count;
  uint64_t index_count;
} SSMeshDescriptor;

int ss_atlas_add_shape(
  void* atlas, const SSKey* key, const float* curves, uint64_t curve_count,
  bool auto_metrics, float bearing_x, float bearing_y, float width, float height,
  float advance, int32_t num_bands_x, int32_t num_bands_y,
  const float* splits_x, uint64_t split_x_count,
  const float* splits_y, uint64_t split_y_count,
  uint32_t origin_type, float origin_x, float origin_y) SS_NOEXCEPT;

/* Returns 1 when found, 0 when absent, and -1 on failure. */
int ss_atlas_get_shape(void* atlas, const SSKey* key, SSShape* out) SS_NOEXCEPT;

int ss_atlas_add_composite(
  void* atlas, const SSKey* key, const SSLayer* layers, uint64_t layer_count,
  float advance) SS_NOEXCEPT;

/* Returns 1 when found, 0 when absent, and -1 on failure. */
int ss_atlas_get_composite(void* atlas, const SSKey* key, float* advance) SS_NOEXCEPT;

/* A null layers pointer queries count. Otherwise capacity must be sufficient.
 * Returns 1 when found, 0 when absent, and -1 on failure. */
int ss_atlas_get_composite_layers(
  void* atlas, const SSKey* key, SSLayer* layers, uint64_t capacity,
  uint64_t* count) SS_NOEXCEPT;

/* kind: 0 = curve, 1 = band. Descriptor bytes remain borrowed from the atlas. */
int ss_atlas_texture(void* atlas, uint32_t kind, SSTextureDescriptor* out) SS_NOEXCEPT;

int ss_shape_quad(
  const SSShape* shape, const SSTransform* transform, float scale,
  SSQuad* out) SS_NOEXCEPT;

/* Returns an owned mesh handle, or null on failure. */
void* ss_mesh_new(void* atlas, const SSLayer* layers, uint64_t layer_count) SS_NOEXCEPT;
int ss_mesh_descriptor(const void* mesh, SSMeshDescriptor* out) SS_NOEXCEPT;
int ss_mesh_destroy(void* mesh) SS_NOEXCEPT;

int ss_atlas_write(void* atlas, const char* path) SS_NOEXCEPT;
void* ss_atlas_read(const char* path) SS_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#undef SS_NOEXCEPT
