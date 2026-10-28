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
void* ss_atlas_new(uint32_t width, int curve_precision) SS_NOEXCEPT;
/* type: 0 = single-channel SDF, 1 = three-channel MSDF. Must precede build. */
int ss_atlas_set_sdf(void* atlas, uint32_t type) SS_NOEXCEPT;

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

typedef struct SSMask SSMask;

int ss_atlas_add_composite(
  void* atlas, const SSKey* key, const SSLayer* layers, uint64_t layer_count,
  float advance, const SSMask* mask) SS_NOEXCEPT;

/* Returns 1 when found, 0 when absent, and -1 on failure. */
int ss_atlas_get_composite(void* atlas, const SSKey* key, float* advance) SS_NOEXCEPT;

/* A null layers pointer queries count. Otherwise capacity must be sufficient.
 * Returns 1 when found, 0 when absent, and -1 on failure. */
int ss_atlas_get_composite_layers(
  void* atlas, const SSKey* key, SSLayer* layers, uint64_t capacity,
  uint64_t* count) SS_NOEXCEPT;

/* kind: 0 = curve, 1 = band, 2 = SDF/MSDF. Descriptor bytes remain borrowed. */
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

typedef struct SSPathSample {
  float x, y, angle, arc_length;
} SSPathSample;

typedef struct SSGradientStop {
  float t;
  SSColor color;
} SSGradientStop;

struct SSMask {
  SSKey key;
  float params[6];
  uint8_t type;
  uint8_t invert;
  uint8_t has_key;
};

typedef struct SSFontMetrics {
  float units_per_em, cap_height_ratio, x_height_ratio;
  float ascender_ratio, descender_ratio;
} SSFontMetrics;

typedef struct SSFontLoadResult {
  uint64_t loaded;
  SSFontMetrics metrics;
  const char* family; /* borrowed until the next ss_font_load on this thread */
  const char* style; /* borrowed until the next ss_font_load on this thread */
} SSFontLoadResult;

/* mode: 0 = explicit codepoints, 1 = printable ASCII, 2 = full charmap. */
int ss_font_load(
  void* atlas, const char* path, const uint32_t* codepoints, uint64_t codepoint_count,
  uint32_t mode, bool uniform, uint8_t mask, SSFontLoadResult* out) SS_NOEXCEPT;

/* A null layers pointer queries count. Otherwise capacity must be sufficient. */
int ss_text_layout(
  void* atlas, const uint32_t* codepoints, uint64_t codepoint_count,
  float font_size, float x, float y, const SSColor* color,
  const SSFontMetrics* metrics, uint32_t anchor_y, uint32_t align_x, uint8_t mask,
  SSLayer* layers, uint64_t capacity, uint64_t* count) SS_NOEXCEPT;

/* Path command IDs and their required float counts:
 * 0 clear/0, 1 save/0, 2 restore/0, 3 reset-transform/0,
 * 4 set-transform/6, 5 transform/6, 6 translate/2, 7 rotate/1, 8 scale/2,
 * 9 move-to/2, 10 line-to/2, 11 quad-to/4, 12 bezier-to/6, 13 close/0,
 * 14 rect/4, 15 rounded-rect/5, 16 circle/3, 17 ellipse/4, 18 arc/6,
 * 19 arc-to/5. Canvas additionally accepts 20 begin-composite/0,
 * 21 set-advance/1, 22 set-auto-metrics/1, 23 set-sdf/2, 24 clear-splits/0. */
void* ss_path_new(void) SS_NOEXCEPT;
int ss_path_destroy(void* path) SS_NOEXCEPT;
int ss_path_command(
  void* path, uint32_t command, const float* values, uint64_t count) SS_NOEXCEPT;
int ss_path_add(void* path, const void* source, const float* matrix) SS_NOEXCEPT;
int ss_path_sample(const void* path, float t, SSPathSample* out) SS_NOEXCEPT;
int ss_path_arc_length(const void* path, float* out) SS_NOEXCEPT;
int ss_path_stroke(
  void* path, float width, bool clockwise, uint32_t join, uint32_t cap,
  float miter_limit) SS_NOEXCEPT;

/* Geometry has four floats: linear x0,y0,x1,y1; radial/affine-radial cx,cy,r0,r1;
 * sweep cx,cy,start-angle,end-angle. An optional six-float matrix overrides the
 * generated paint transform and is required for affine-radial gradients. */
int64_t ss_atlas_add_gradient(
  void* atlas, uint32_t type, const float* geometry, uint64_t geometry_count,
  const SSGradientStop* stops, uint64_t stop_count, const float* matrix) SS_NOEXCEPT;
int64_t ss_atlas_add_pattern(
  void* atlas, const SSKey* tile, const float* cell, const float* matrix,
  uint32_t repeat_x, uint32_t repeat_y) SS_NOEXCEPT;
int ss_atlas_get_composite_mask(
  void* atlas, const SSKey* key, SSMask* out) SS_NOEXCEPT;

void* ss_canvas_new(void* atlas, uint32_t key_start) SS_NOEXCEPT;
int ss_canvas_destroy(void* canvas) SS_NOEXCEPT;
int ss_canvas_command(
  void* canvas, uint32_t command, const float* values, uint64_t count) SS_NOEXCEPT;
int ss_canvas_add_path(void* canvas, const void* path, const float* matrix) SS_NOEXCEPT;
int ss_canvas_set_splits(
  void* canvas, const float* splits_x, uint64_t split_x_count,
  const float* splits_y, uint64_t split_y_count) SS_NOEXCEPT;
/* Returned named keys borrow canvas-owned storage, valid until the next
 * fill/stroke commit or canvas destruction. */
int ss_canvas_fill(
  void* canvas, const void* path, const SSKey* key, SSColor color,
  float scale, uint32_t origin_type, float origin_x, float origin_y,
  uint32_t gradient_id, uint32_t pattern_id, SSLayer* out) SS_NOEXCEPT;
int ss_canvas_stroke(
  void* canvas, const void* path, const SSKey* key, float width, SSColor color,
  float scale, uint32_t origin_type, float origin_x, float origin_y,
  uint32_t join, uint32_t cap, float miter_limit,
  uint32_t gradient_id, uint32_t pattern_id, SSLayer* out) SS_NOEXCEPT;
int ss_canvas_define_shape(
  void* canvas, const void* path, const SSKey* key, float scale,
  uint32_t origin_type, float origin_x, float origin_y) SS_NOEXCEPT;
int ss_canvas_text(
  void* canvas, const char* text, float font_size, float x, float y, SSColor color,
  const SSFontMetrics* metrics, uint32_t anchor_y, uint32_t align_x,
  uint8_t mask) SS_NOEXCEPT;
int ss_canvas_finalize(void* canvas, const SSKey* key) SS_NOEXCEPT;
int ss_canvas_set_mask(void* canvas, const SSMask* mask) SS_NOEXCEPT;
int ss_canvas_mask_path(void* canvas, float range, bool invert, SSMask* out) SS_NOEXCEPT;

typedef struct SSLayersView {
  const SSLayer* data;
  uint64_t count;
} SSLayersView;

typedef struct SSGLContextOptions {
  const char* title;
  int32_t width, height;
  float bounds[4];
  uint8_t has_bounds, visible;
} SSGLContextOptions;

typedef struct SSGLUniform {
  const char* name;
  uint32_t type;
  uint32_t components;
  float values[16];
  int32_t int_values[16];
  uint32_t uint_values[16];
} SSGLUniform;

typedef struct SSGLDrawCommand {
  void* handle;
  void* paint;
  void* effect;
  SSTransform transform;
  SSColor color;
  float scale;
  uint32_t effect_id;
  float effect_param;
  float bleed;
  uint8_t visible;
  uint8_t kind; /* 0 = draw, 1 = begin-group, 2 = end-group */
  uint8_t draw_mode;
  uint8_t blend_mode;
  uint8_t mask_type; /* 255 = no mask */
  uint8_t mask_invert;
  uint8_t reserved[2];
  float mask_params[6];
} SSGLDrawCommand;

typedef struct SSGLFrameState {
  int32_t viewport[4];
  int32_t framebuffer_binding;
  int32_t framebuffer_size[2];
  int32_t window_size[2];
  float projection[16];
  uint32_t shader_compilations, resource_uploads, paint_texture_uploads;
  float effect_param;
} SSGLFrameState;

typedef struct SSGLInput {
  double x, y, scroll_x, scroll_y;
  uint8_t left, right, middle, space, r;
} SSGLInput;

typedef struct SSGLCursor {
  double x, y;
} SSGLCursor;

void* ss_gl_context_create(const SSGLContextOptions* options) SS_NOEXCEPT;
int ss_gl_context_destroy(void* context) SS_NOEXCEPT;
int ss_gl_poll(void* context) SS_NOEXCEPT;
bool ss_gl_should_close(void* context) SS_NOEXCEPT;
int ss_gl_input_snapshot(void* context, SSGLInput* out) SS_NOEXCEPT;
int ss_gl_cursor_snapshot(void* context, SSGLCursor* out) SS_NOEXCEPT;
int ss_gl_request_close(void* context) SS_NOEXCEPT;
void* ss_gl_program(void* context, const char* shader_key) SS_NOEXCEPT;
void* ss_gl_effect_program(
  void* context, const char* program_key, const char* effect_key,
  const char* declarations, const char* vertex_hook, const char* fragment_hook,
  uint8_t requires_sdf, const SSGLUniform* uniforms, uint64_t uniform_count) SS_NOEXCEPT;
void* ss_gl_upload(void* context, void* atlas, const SSLayer* root) SS_NOEXCEPT;
void* ss_gl_paint_texture(void* context, void* resource) SS_NOEXCEPT;
int ss_gl_release(void* context, void* handle) SS_NOEXCEPT;
int ss_gl_render(void* context, const SSGLDrawCommand* commands, uint64_t count) SS_NOEXCEPT;
/* Index of the command that caused the last render error on this thread, or -1. */
int64_t ss_gl_error_command(void* context) SS_NOEXCEPT;
int ss_gl_swap(void* context) SS_NOEXCEPT;

/* Native integration instrumentation: resize the real window, read back GL
 * state and copy the current framebuffer as bottom-up RGBA8 pixels. */
int ss_gl_test_set_input(void* context, const SSGLInput* input) SS_NOEXCEPT;
int ss_gl_test_resize(void* context, int32_t width, int32_t height) SS_NOEXCEPT;
int ss_gl_test_bind_nondefault(
  void* context, int32_t x, int32_t y, int32_t width, int32_t height) SS_NOEXCEPT;
int ss_gl_test_restore_default(void* context) SS_NOEXCEPT;
int ss_gl_test_fail_next(void* context, uint32_t point) SS_NOEXCEPT;
int ss_gl_test_frame_state(void* context, SSGLFrameState* out) SS_NOEXCEPT;
int ss_gl_test_capture(void* context, uint8_t* out, uint64_t capacity) SS_NOEXCEPT;

/* The atlas and layer storage are borrowed for the duration of this blocking
 * call. The atlas must be built and remain alive until the renderer exits. */
int ss_glfw_run(
  void* atlas, SSLayersView layers, const char* title, int curve_precision,
  const char* capture_path, int max_frames) SS_NOEXCEPT;

/* Returns null for a terminal, or an owned UTF-8 byte string for piped stdin.
 * Free non-null results with ss_free_stdin_text. */
char* ss_read_stdin_text(void) SS_NOEXCEPT;
void ss_free_stdin_text(char* text) SS_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#undef SS_NOEXCEPT
