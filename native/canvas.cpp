#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

#include "slughorn/canvas.hpp"

#include <utility>
#include <vector>

namespace {

using Canvas = slughorn::canvas::Canvas;
using Path = slughorn::canvas::Path;

struct PaintOverride {
  size_t layer_index;
  uint32_t gradient_id;
  uint32_t pattern_id;
};

struct NativeCanvas {
  NativeCanvas(slughorn::Atlas& owner, uint32_t key_start): atlas(owner), canvas(owner, slughorn::KeyIterator(key_start)) {}
  slughorn::Atlas& atlas;
  Canvas canvas;
  std::vector<PaintOverride> overrides;
  std::string returned_key_name;
};

NativeCanvas* require_canvas(void* handle) {
  if(!handle) throw std::invalid_argument("canvas handle is null");
  return static_cast<NativeCanvas*>(handle);
}

Path* require_path(void* handle) {
  if(!handle) throw std::invalid_argument("path handle is null");
  return static_cast<Path*>(handle);
}

const Path* require_path(const void* handle) {
  if(!handle) throw std::invalid_argument("path handle is null");
  return static_cast<const Path*>(handle);
}

const float* require_values(const float* values, uint64_t count, uint64_t expected) {
  if(count != expected) throw std::invalid_argument("wrong canvas command argument count");
  if(expected > 0 && !values) throw std::invalid_argument("canvas command values are null");
  return values;
}

slughorn::Key from_c(const SSKey* key) {
  if(!key) throw std::invalid_argument("key is null");
  switch(key->type) {
    case 0: return slughorn::Key(key->codepoint);
    case 1:
      if(!key->name) throw std::invalid_argument("named key has null name");
      return slughorn::Key(key->name);
    default: throw std::invalid_argument("unknown key type");
  }
}

SSKey to_c(const slughorn::Key& key) {
  if(key.type() == slughorn::Key::Type::Codepoint) return {0, key.codepoint(), nullptr};
  return {1, 0, key.name().c_str()};
}

slughorn::Color from_c(SSColor color) {
  return {color.r, color.g, color.b, color.a};
}

SSLayer to_c(const slughorn::Layer& layer) {
  return {
    to_c(layer.key),
    {layer.color.r, layer.color.g, layer.color.b, layer.color.a},
    {layer.transform.x, layer.transform.y, layer.transform.z},
    layer.scale, layer.effectId, layer.effectParam, layer.gradientId, layer.patternId,
    layer.bleed, static_cast<uint8_t>(layer.drawMode), static_cast<uint8_t>(layer.blendMode)
  };
}

SSLayer layer_to_c(NativeCanvas& target, const slughorn::Layer& layer) {
  auto out = to_c(layer);
  if(layer.key.type() == slughorn::Key::Type::Name) {
    target.returned_key_name = layer.key.name();
    out.key.name = target.returned_key_name.c_str();
  }
  return out;
}

slughorn::Matrix matrix_from_c(const float* values) {
  if(!values) return {};
  return {values[0], values[1], values[2], values[3], values[4], values[5]};
}

slughorn::Atlas::ShapeInfo::Origin origin_from_c(
  uint32_t type, float x, float y
) {
  if(type > 3) throw std::invalid_argument("unknown origin type");
  return {static_cast<slughorn::Atlas::ShapeInfo::Origin::Type>(type), x, y};
}

std::vector<slughorn::slug_t> floats_from_c(const float* values, uint64_t count) {
  if(count == 0) return {};
  if(!values) throw std::invalid_argument("split values are null");
  if(count > SIZE_MAX / sizeof(float)) throw std::invalid_argument("split count is too large");
  return {values, values + count};
}

slughorn::Mask mask_from_c(const SSMask& mask) {
  if(mask.type > 8) throw std::invalid_argument("unknown mask type");
  slughorn::Mask out;
  out.type = static_cast<slughorn::Mask::Type>(mask.type);
  out.invert = mask.invert != 0;
  for(size_t i = 0; i < 6; ++i) out.params[i] = mask.params[i];
  if(mask.has_key) out.key = from_c(&mask.key);
  if(out.type == slughorn::Mask::Type::SDFTile && !out.key) {
    throw std::invalid_argument("SDF tile mask requires a key");
  }
  return out;
}

SSMask to_c(const slughorn::Mask& mask) {
  SSMask out = {};
  if(mask.key) {
    out.key = to_c(*mask.key);
    out.has_key = 1;
  }
  out.type = static_cast<uint8_t>(mask.type);
  out.invert = mask.invert ? 1 : 0;
  for(size_t i = 0; i < 6; ++i) out.params[i] = mask.params[i];
  return out;
}

void validate_paint(uint32_t gradient_id, uint32_t pattern_id) {
  if(gradient_id != 0 && pattern_id != 0) {
    throw std::invalid_argument("gradient and pattern paints are mutually exclusive");
  }
}

void record_paint(
  NativeCanvas& target, size_t previous_count, slughorn::Layer& layer,
  uint32_t gradient_id, uint32_t pattern_id
) {
  if(target.canvas.layerCount() <= previous_count) {
    throw std::runtime_error("cannot commit an empty or degenerate path");
  }
  layer.gradientId = gradient_id;
  layer.patternId = pattern_id;
  target.overrides.push_back({previous_count, gradient_id, pattern_id});
}

}

void* ss_canvas_new(void* atlas, uint32_t key_start) noexcept {
  ss_try(nullptr, return new NativeCanvas(*slipstream::require_atlas(atlas), key_start););
}

int ss_canvas_destroy(void* canvas) noexcept {
  ss_try(-1, delete require_canvas(canvas); return 0;);
}

int ss_canvas_command(
  void* canvas, uint32_t command, const float* values, uint64_t count
) noexcept {
  ss_try(-1,
    auto& c = require_canvas(canvas)->canvas;
    switch(command) {
      case 0: require_values(values, count, 0); c.beginPath(); break;
      case 1: require_values(values, count, 0); c.save(); break;
      case 2: require_values(values, count, 0); c.restore(); break;
      case 3: require_values(values, count, 0); c.resetTransform(); break;
      case 4: require_values(values, count, 6); c.setTransform(matrix_from_c(values)); break;
      case 5: require_values(values, count, 6); c.transform(matrix_from_c(values)); break;
      case 6: require_values(values, count, 2); c.translate(values[0], values[1]); break;
      case 7: require_values(values, count, 1); c.rotate(values[0]); break;
      case 8: require_values(values, count, 2); c.scale(values[0], values[1]); break;
      case 9: require_values(values, count, 2); c.moveTo(values[0], values[1]); break;
      case 10: require_values(values, count, 2); c.lineTo(values[0], values[1]); break;
      case 11: require_values(values, count, 4); c.quadTo(values[0], values[1], values[2], values[3]); break;
      case 12: require_values(values, count, 6); c.bezierTo(values[0], values[1], values[2], values[3], values[4], values[5]); break;
      case 13: require_values(values, count, 0); c.closePath(); break;
      case 14: require_values(values, count, 4); c.rect(values[0], values[1], values[2], values[3]); break;
      case 15: require_values(values, count, 5); c.roundedRect(values[0], values[1], values[2], values[3], values[4]); break;
      case 16: require_values(values, count, 3); c.circle(values[0], values[1], values[2]); break;
      case 17: require_values(values, count, 4); c.ellipse(values[0], values[1], values[2], values[3]); break;
      case 18: require_values(values, count, 6); c.arc(values[0], values[1], values[2], values[3], values[4], values[5] != 0); break;
      case 19: require_values(values, count, 5); c.arcTo(values[0], values[1], values[2], values[3], values[4]); break;
      case 20: require_values(values, count, 0); c.beginComposite(); require_canvas(canvas)->overrides.clear(); break;
      case 21: require_values(values, count, 1); c.setAdvance(values[0]); break;
      case 22: require_values(values, count, 1); c.setAutoMetrics(values[0] != 0); break;
      case 23: require_values(values, count, 2); c.setSDF(values[0] != 0, values[1]); break;
      case 24: require_values(values, count, 0); c.clearSplits(); break;
      default: throw std::invalid_argument("unknown canvas command");
    }
    return 0;
  );
}

int ss_canvas_add_path(void* canvas, const void* path, const float* matrix) noexcept {
  ss_try(-1,
    auto& target = require_canvas(canvas)->canvas;
    const auto* source = require_path(path);
    if(matrix) target.addPath(*source, matrix_from_c(matrix));
    else target.addPath(*source);
    return 0;
  );
}

int ss_canvas_set_splits(
  void* canvas, const float* splits_x, uint64_t split_x_count,
  const float* splits_y, uint64_t split_y_count
) noexcept {
  ss_try(-1,
    require_canvas(canvas)->canvas.setSplits(
      floats_from_c(splits_x, split_x_count), floats_from_c(splits_y, split_y_count));
    return 0;
  );
}

int ss_canvas_fill(
  void* canvas, const void* path, const SSKey* key, SSColor color,
  float scale, uint32_t origin_type, float origin_x, float origin_y,
  uint32_t gradient_id, uint32_t pattern_id, SSLayer* out
) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("fill layer output is null");
    validate_paint(gradient_id, pattern_id);
    auto* target = require_canvas(canvas);
    const auto origin = origin_from_c(origin_type, origin_x, origin_y);
    const size_t previous = target->canvas.layerCount();
    slughorn::Layer layer;
    if(path) {
      const auto* p = require_path(path);
      layer = key ? target->canvas.fill(*p, from_c(color), scale, from_c(key), origin)
                  : target->canvas.fill(*p, from_c(color), scale, origin);
    } else {
      layer = key ? target->canvas.fill(from_c(color), scale, from_c(key), origin)
                  : target->canvas.fill(from_c(color), scale, origin);
    }
    record_paint(*target, previous, layer, gradient_id, pattern_id);
    *out = layer_to_c(*target, layer);
    return 0;
  );
}

int ss_canvas_stroke(
  void* canvas, const void* path, const SSKey* key, float width, SSColor color,
  float scale, uint32_t origin_type, float origin_x, float origin_y,
  uint32_t join, uint32_t cap, float miter_limit,
  uint32_t gradient_id, uint32_t pattern_id, SSLayer* out
) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("stroke layer output is null");
    if(width <= 0) throw std::invalid_argument("stroke width must be greater than zero");
    if(join > 2) throw std::invalid_argument("unknown stroke join");
    if(cap > 2) throw std::invalid_argument("unknown stroke cap");
    validate_paint(gradient_id, pattern_id);
    auto* target = require_canvas(canvas);
    const auto origin = origin_from_c(origin_type, origin_x, origin_y);
    const auto native_join = static_cast<slughorn::canvas::LineJoin>(join);
    const auto native_cap = static_cast<slughorn::canvas::LineCap>(cap);
    const size_t previous = target->canvas.layerCount();
    slughorn::Layer layer;
    if(path) {
      const auto* p = require_path(path);
      layer = key ? target->canvas.stroke(*p, width, from_c(color), scale, from_c(key), origin, native_join, native_cap, miter_limit)
                  : target->canvas.stroke(*p, width, from_c(color), scale, origin, native_join, native_cap, miter_limit);
    } else {
      layer = key ? target->canvas.stroke(width, from_c(color), scale, from_c(key), origin, native_join, native_cap, miter_limit)
                  : target->canvas.stroke(width, from_c(color), scale, origin, native_join, native_cap, miter_limit);
    }
    record_paint(*target, previous, layer, gradient_id, pattern_id);
    *out = layer_to_c(*target, layer);
    return 0;
  );
}

int ss_canvas_define_shape(
  void* canvas, const void* path, const SSKey* key, float scale,
  uint32_t origin_type, float origin_x, float origin_y
) noexcept {
  ss_try(-1,
    auto& c = require_canvas(canvas)->canvas;
    const auto origin = origin_from_c(origin_type, origin_x, origin_y);
    const bool committed = path
      ? c.defineShape(*require_path(path), from_c(key), scale, origin)
      : c.defineShape(from_c(key), scale, origin);
    if(!committed) throw std::runtime_error("cannot define an empty or degenerate path");
    return 0;
  );
}

int ss_canvas_text(
  void* canvas, const char* text, float font_size, float x, float y, SSColor color,
  const SSFontMetrics* metrics, uint32_t anchor_y, uint32_t align_x, uint8_t mask
) noexcept {
  ss_try(-1,
    if(!text) throw std::invalid_argument("text is null");
    if(!metrics) throw std::invalid_argument("font metrics are null");
    if(anchor_y > 3) throw std::invalid_argument("unknown vertical text anchor");
    if(align_x > 2) throw std::invalid_argument("unknown horizontal text alignment");
    const slughorn::FontMetrics native_metrics{
      metrics->units_per_em, metrics->cap_height_ratio, metrics->x_height_ratio,
      metrics->ascender_ratio, metrics->descender_ratio
    };
    require_canvas(canvas)->canvas.text(
      text, font_size, x, y, from_c(color), native_metrics,
      static_cast<slughorn::canvas::TextAnchorY>(anchor_y),
      static_cast<slughorn::canvas::TextAlignX>(align_x), mask);
    return 0;
  );
}

int ss_canvas_finalize(void* canvas, const SSKey* key) noexcept {
  ss_try(-1,
    auto* target = require_canvas(canvas);
    auto composite = target->canvas.finalize();
    for(const auto& paint : target->overrides) {
      if(paint.layer_index >= composite.layers.size()) {
        throw std::runtime_error("canvas paint override is out of range");
      }
      composite.layers[paint.layer_index].gradientId = paint.gradient_id;
      composite.layers[paint.layer_index].patternId = paint.pattern_id;
    }
    target->overrides.clear();
    target->atlas.addCompositeShape(from_c(key), std::move(composite));
    return 0;
  );
}

int ss_canvas_set_mask(void* canvas, const SSMask* mask) noexcept {
  ss_try(-1,
    auto& c = require_canvas(canvas)->canvas;
    if(mask) c.mask(mask_from_c(*mask));
    else c.mask(slughorn::Mask{});
    return 0;
  );
}

int ss_canvas_mask_path(void* canvas, float range, bool invert, SSMask* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("mask output is null");
    const auto mask = require_canvas(canvas)->canvas.mask(range, invert);
    if(!mask.key) throw std::runtime_error("cannot create a mask from an empty path");
    *out = to_c(mask);
    return 0;
  );
}

int64_t ss_atlas_add_gradient(
  void* atlas, uint32_t type, const float* geometry, uint64_t geometry_count,
  const SSGradientStop* stops, uint64_t stop_count
) noexcept {
  ss_try(-1,
    auto* target = slipstream::require_atlas(atlas);
    if(target->isBuilt()) throw std::runtime_error("cannot add a gradient after atlas build");
    if(type > 2) throw std::invalid_argument("unknown gradient type");
    require_values(geometry, geometry_count, 4);
    if(stop_count == 0) throw std::invalid_argument("gradient requires at least one stop");
    if(!stops) throw std::invalid_argument("gradient stops are null");
    if(stop_count > SIZE_MAX / sizeof(slughorn::GradientStop)) {
      throw std::invalid_argument("gradient stop count is too large");
    }
    slughorn::GradientInfo info;
    info.type = static_cast<slughorn::GradientInfo::Type>(type);
    info.stops.reserve(stop_count);
    for(uint64_t i = 0; i < stop_count; ++i) {
      info.stops.push_back({stops[i].t, from_c(stops[i].color)});
    }
    switch(info.type) {
      case slughorn::GradientInfo::Type::Linear:
        info.transform = slughorn::buildLinearGradientMatrix(geometry[0], geometry[1], geometry[2], geometry[3]);
        break;
      case slughorn::GradientInfo::Type::Radial:
        info.innerRadius = geometry[2];
        info.transform = slughorn::buildRadialGradientMatrix(geometry[0], geometry[1], geometry[3]);
        break;
      case slughorn::GradientInfo::Type::Sweep:
        info.transform = slughorn::buildSweepGradientMatrix(geometry[0], geometry[1], geometry[2], geometry[3] - geometry[2]);
        break;
      default: throw std::invalid_argument("unsupported gradient type");
    }
    const uint32_t id = target->addGradient(info);
    if(id == 0) throw std::runtime_error("gradient registration failed");
    return static_cast<int64_t>(id);
  );
}

int64_t ss_atlas_add_pattern(
  void* atlas, const SSKey* tile, const float* cell, const float* matrix,
  uint32_t repeat_x, uint32_t repeat_y
) noexcept {
  ss_try(-1,
    auto* target = slipstream::require_atlas(atlas);
    if(target->isBuilt()) throw std::runtime_error("cannot add a pattern after atlas build");
    if(!cell) throw std::invalid_argument("pattern cell is null");
    if(!matrix) throw std::invalid_argument("pattern transform is null");
    if(repeat_x > 1 || repeat_y > 1) throw std::invalid_argument("unknown pattern repeat mode");
    slughorn::PatternInfo info;
    info.tile = from_c(tile);
    info.cellX = cell[0]; info.cellY = cell[1];
    info.cellWidth = cell[2]; info.cellHeight = cell[3];
    info.transform = matrix_from_c(matrix);
    info.repeatX = static_cast<slughorn::PatternInfo::Repeat>(repeat_x);
    info.repeatY = static_cast<slughorn::PatternInfo::Repeat>(repeat_y);
    const uint32_t id = target->addPattern(info);
    if(id == 0) throw std::runtime_error("pattern registration failed");
    return static_cast<int64_t>(id);
  );
}

int ss_atlas_get_composite_mask(void* atlas, const SSKey* key, SSMask* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("mask output is null");
    const auto* composite = slipstream::require_atlas(atlas)->getCompositeShape(from_c(key));
    if(!composite) return 0;
    *out = composite->mask ? to_c(*composite->mask) : SSMask{};
    return 1;
  );
}
