#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

#define SLUGHORN_SERIAL_IMPLEMENTATION
#include "slughorn/serial.hpp"

#include <vector>

namespace {

slughorn::Key from_c(const SSKey* key) {
  if(!key) throw std::invalid_argument("key is null");

  switch(key->type) {
    case 0:
      return slughorn::Key(key->codepoint);
    case 1:
      if(!key->name) throw std::invalid_argument("named key has null name");
      return slughorn::Key(key->name);
    default:
      throw std::invalid_argument("unknown key type");
  }
}

SSKey to_c(const slughorn::Key& key) {
  if(key.type() == slughorn::Key::Type::Codepoint) {
    return {0, key.codepoint(), nullptr};
  }
  return {1, 0, key.name().c_str()};
}

slughorn::Layer from_c(const SSLayer& layer) {
  slughorn::Layer out;
  out.key = from_c(&layer.key);
  out.color = {layer.color.r, layer.color.g, layer.color.b, layer.color.a};
  out.transform = {layer.transform.x, layer.transform.y, layer.transform.z};
  out.scale = layer.scale;
  out.effectId = layer.effect_id;
  out.effectParam = layer.effect_param;
  out.gradientId = layer.gradient_id;
  out.patternId = layer.pattern_id;
  out.bleed = layer.bleed;
  out.drawMode = static_cast<slughorn::DrawMode>(layer.draw_mode);
  out.blendMode = static_cast<slughorn::BlendMode>(layer.blend_mode);
  return out;
}

SSLayer to_c(const slughorn::Layer& layer) {
  return {
    to_c(layer.key),
    {layer.color.r, layer.color.g, layer.color.b, layer.color.a},
    {layer.transform.x, layer.transform.y, layer.transform.z},
    layer.scale,
    layer.effectId,
    layer.effectParam,
    layer.gradientId,
    layer.patternId,
    layer.bleed,
    static_cast<uint8_t>(layer.drawMode),
    static_cast<uint8_t>(layer.blendMode)
  };
}

SSShape to_c(const slughorn::Atlas::Shape& shape) {
  return {
    shape.bandTexX, shape.bandTexY, shape.bandMaxX, shape.bandMaxY,
    shape.bandScaleX, shape.bandScaleY, shape.bandOffsetX, shape.bandOffsetY,
    shape.bearingX, shape.bearingY, shape.width, shape.height, shape.advance,
    shape.originX, shape.originY,
    static_cast<uint32_t>(shape.origin.type), shape.origin.x, shape.origin.y
  };
}

std::vector<slughorn::Atlas::Curve> curves_from_c(
  const float* curves, uint64_t curve_count
) {
  if(curve_count > 0 && !curves) throw std::invalid_argument("curves are null");
  if(curve_count > (SIZE_MAX / sizeof(slughorn::Atlas::Curve))) {
    throw std::invalid_argument("curve count is too large");
  }

  std::vector<slughorn::Atlas::Curve> out;
  out.reserve(static_cast<size_t>(curve_count));
  for(uint64_t i = 0; i < curve_count; ++i) {
    const float* c = curves + i * 6;
    out.push_back({c[0], c[1], c[2], c[3], c[4], c[5]});
  }
  return out;
}

std::vector<slughorn::slug_t> floats_from_c(const float* values, uint64_t count) {
  if(count == 0) return {};
  if(!values) throw std::invalid_argument("split values are null");
  if(count > SIZE_MAX / sizeof(float)) throw std::invalid_argument("split count is too large");
  return std::vector<slughorn::slug_t>(values, values + count);
}

SSTextureDescriptor texture_to_c(const slughorn::Atlas::TextureData& texture) {
  return {
    texture.bytes.data(), static_cast<uint64_t>(texture.bytes.size()),
    texture.width, texture.height, texture.depth,
    static_cast<uint32_t>(texture.format)
  };
}

}

int ss_atlas_add_shape(
  void* atlas, const SSKey* key, const float* curves, uint64_t curve_count,
  bool auto_metrics, float bearing_x, float bearing_y, float width, float height,
  float advance, int32_t num_bands_x, int32_t num_bands_y,
  const float* splits_x, uint64_t split_x_count,
  const float* splits_y, uint64_t split_y_count,
  uint32_t origin_type, float origin_x, float origin_y
) noexcept {
  ss_try(-1,
    auto* target = slipstream::require_atlas(atlas);
    if(target->isBuilt()) throw std::runtime_error("cannot add a shape after atlas build");
    if(num_bands_x < 0 || num_bands_y < 0) {
      throw std::invalid_argument("band counts cannot be negative");
    }
    if(origin_type > 3) throw std::invalid_argument("unknown origin type");

    slughorn::Atlas::ShapeInfo info;
    info.curves = curves_from_c(curves, curve_count);
    info.autoMetrics = auto_metrics;
    info.bearingX = bearing_x;
    info.bearingY = bearing_y;
    info.width = width;
    info.height = height;
    info.advance = advance;
    info.numBandsX = num_bands_x;
    info.numBandsY = num_bands_y;
    info.splitsX = floats_from_c(splits_x, split_x_count);
    info.splitsY = floats_from_c(splits_y, split_y_count);
    info.origin = slughorn::Atlas::ShapeInfo::Origin(
      static_cast<slughorn::Atlas::ShapeInfo::Origin::Type>(origin_type),
      origin_x, origin_y
    );
    target->addShape(from_c(key), info);
    return 0;
  );
}

int ss_atlas_get_shape(void* atlas, const SSKey* key, SSShape* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("shape output is null");
    const auto shape = slipstream::require_atlas(atlas)->getShape(from_c(key));
    if(!shape) return 0;
    *out = to_c(*shape);
    return 1;
  );
}

int ss_atlas_add_composite(
  void* atlas, const SSKey* key, const SSLayer* layers, uint64_t layer_count,
  float advance
) noexcept {
  ss_try(-1,
    if(layer_count > 0 && !layers) throw std::invalid_argument("layers are null");
    if(layer_count > SIZE_MAX / sizeof(slughorn::Layer)) {
      throw std::invalid_argument("layer count is too large");
    }

    slughorn::CompositeShape composite;
    composite.advance = advance;
    composite.layers.reserve(static_cast<size_t>(layer_count));
    for(uint64_t i = 0; i < layer_count; ++i) {
      composite.layers.push_back(from_c(layers[i]));
    }
    slipstream::require_atlas(atlas)->addCompositeShape(from_c(key), std::move(composite));
    return 0;
  );
}

int ss_atlas_get_composite(void* atlas, const SSKey* key, float* advance) noexcept {
  ss_try(-1,
    if(!advance) throw std::invalid_argument("advance output is null");
    const auto* composite = slipstream::require_atlas(atlas)->getCompositeShape(from_c(key));
    if(!composite) return 0;
    *advance = composite->advance;
    return 1;
  );
}

int ss_atlas_get_composite_layers(
  void* atlas, const SSKey* key, SSLayer* layers, uint64_t capacity,
  uint64_t* count
) noexcept {
  ss_try(-1,
    if(!count) throw std::invalid_argument("layer count output is null");
    const auto* composite = slipstream::require_atlas(atlas)->getCompositeShape(from_c(key));
    if(!composite) return 0;

    *count = composite->layers.size();
    if(layers) {
      if(capacity < *count) throw std::invalid_argument("layer buffer capacity is too small");
      for(size_t i = 0; i < composite->layers.size(); ++i) {
        layers[i] = to_c(composite->layers[i]);
      }
    }
    return 1;
  );
}

int ss_atlas_texture(void* atlas, uint32_t kind, SSTextureDescriptor* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("texture output is null");
    const auto* source = slipstream::require_atlas(atlas);
    switch(kind) {
      case 0: *out = texture_to_c(source->getCurveTextureData()); return 0;
      case 1: *out = texture_to_c(source->getBandTextureData()); return 0;
      default: throw std::invalid_argument("unknown texture kind");
    }
  );
}

int ss_shape_quad(
  const SSShape* shape, const SSTransform* transform, float scale, SSQuad* out
) noexcept {
  ss_try(-1,
    if(!shape || !transform || !out) throw std::invalid_argument("quad argument is null");
    slughorn::Atlas::Shape native;
    native.bearingX = shape->bearing_x;
    native.bearingY = shape->bearing_y;
    native.width = shape->width;
    native.height = shape->height;
    native.originX = shape->origin_x;
    native.originY = shape->origin_y;
    const auto quad = native.computeQuad({transform->x, transform->y, transform->z}, scale);
    *out = {quad.x0, quad.y0, quad.x1, quad.y1};
    return 0;
  );
}

int ss_atlas_write(void* atlas, const char* path) noexcept {
  ss_try(-1,
    if(!path) throw std::invalid_argument("path is null");
    slughorn::serial::write(*slipstream::require_atlas(atlas), path);
    return 0;
  );
}

void* ss_atlas_read(const char* path) noexcept {
  ss_try(nullptr,
    if(!path) throw std::invalid_argument("path is null");
    return new slughorn::Atlas(slughorn::serial::read(path));
  );
}
