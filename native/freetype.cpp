#include "slipstream_shim.h"
#include "slipstream_internal.hpp"
#include "slughorn/canvas.hpp"

#define SLUGHORN_FREETYPE_IMPLEMENTATION
#include "slughorn/freetype.hpp"

#include <string>
#include <vector>

namespace {

thread_local std::string last_family;
thread_local std::string last_style;

SSKey key_to_c(const slughorn::Key& key) {
  return {0, key.codepoint(), nullptr};
}

SSLayer layer_to_c(const slughorn::Layer& layer) {
  return {
    key_to_c(layer.key),
    {layer.color.r, layer.color.g, layer.color.b, layer.color.a},
    {layer.transform.x, layer.transform.y, layer.transform.z},
    layer.scale, layer.effectId, layer.effectParam, layer.gradientId, layer.patternId,
    layer.bleed, static_cast<uint8_t>(layer.drawMode), static_cast<uint8_t>(layer.blendMode)
  };
}

}

namespace slipstream {

std::vector<slughorn::Layer> layout_text(
  slughorn::Atlas& atlas, const uint32_t* codepoints, size_t count,
  float font_size, float x, float y, slughorn::Color color,
  const slughorn::FontMetrics& metrics, uint32_t anchor_y, uint32_t align_x,
  uint8_t mask
) {
  if(anchor_y > 3) throw std::invalid_argument("unknown vertical text anchor");
  if(align_x > 2) throw std::invalid_argument("unknown horizontal text alignment");
  if(count > 0 && !codepoints) throw std::invalid_argument("text codepoints are null");

  std::vector<slughorn::Layer> layers;
  if(count == 0 || font_size == 0) return layers;

  layers.reserve(count);
  float dx = x / font_size;
  float dy = y / font_size;

  switch(static_cast<slughorn::canvas::TextAnchorY>(anchor_y)) {
    case slughorn::canvas::TextAnchorY::Baseline: break;
    case slughorn::canvas::TextAnchorY::CapCenter: dy -= metrics.capHeightRatio * 0.5f; break;
    case slughorn::canvas::TextAnchorY::CapTop: dy -= metrics.capHeightRatio; break;
    case slughorn::canvas::TextAnchorY::XCenter: dy -= metrics.xHeightRatio * 0.5f; break;
  }

  if(align_x != 0) {
    float total_advance = 0;
    for(size_t i = 0; i < count; ++i) {
      const auto shape = atlas.getShape(slughorn::Key(codepoints[i], mask));
      total_advance += shape ? shape->advance : 0.6f;
    }
    if(align_x == 1) dx -= total_advance * 0.5f;
    else dx -= total_advance;
  }

  for(size_t i = 0; i < count; ++i) {
    const slughorn::Key key(codepoints[i], mask);
    const auto shape = atlas.getShape(key);
    layers.push_back({
      .key = key,
      .color = color,
      .transform = {.x = dx, .y = dy},
      .scale = font_size
    });
    dx += shape ? shape->advance : 0.6f;
  }

  return layers;
}

}

int ss_font_load(
  void* atlas, const char* path, const uint32_t* codepoints, uint64_t codepoint_count,
  uint32_t mode, bool uniform, uint8_t mask, SSFontLoadResult* out
) noexcept {
  ss_try(-1,
    auto* target = slipstream::require_atlas(atlas);
    if(!path) throw std::invalid_argument("font path is null");
    if(!out) throw std::invalid_argument("font load output is null");
    if(target->isBuilt()) throw std::runtime_error("cannot load a font after atlas build");
    if(codepoint_count > 0 && !codepoints) throw std::invalid_argument("font codepoints are null");
    if(codepoint_count > SIZE_MAX) throw std::invalid_argument("font codepoint count is too large");

    slughorn::freetype::LoadConfig config;
    config.uniform = uniform;
    config.mask = mask;
    size_t loaded = 0;

    switch(mode) {
      case 0: {
        std::vector<uint32_t> cps;
        if(codepoint_count > 0) {
          cps.assign(codepoints, codepoints + static_cast<size_t>(codepoint_count));
        }
        loaded = slughorn::freetype::loadFontGlyphs(path, cps, *target, &config);
        break;
      }
      case 1:
        if(!slughorn::freetype::loadAsciiFont(path, *target, &config)) {
          throw std::runtime_error(std::string("failed to open font: ") + path);
        }
        for(uint32_t cp = 32; cp <= 126; ++cp) {
          if(target->hasKey(slughorn::Key(cp, mask))) ++loaded;
        }
        break;
      case 2:
        loaded = slughorn::freetype::loadAllFontGlyphs(path, *target, &config);
        break;
      default: throw std::invalid_argument("unknown font loading mode");
    }

    last_family = config.familyName;
    last_style = config.styleName;
    out->loaded = loaded;
    out->metrics = {
      config.metrics.unitsPerEM, config.metrics.capHeightRatio,
      config.metrics.xHeightRatio, config.metrics.ascenderRatio,
      config.metrics.descenderRatio
    };
    out->family = last_family.c_str();
    out->style = last_style.c_str();
    return 0;
  );
}

int ss_text_layout(
  void* atlas, const uint32_t* codepoints, uint64_t codepoint_count,
  float font_size, float x, float y, const SSColor* color,
  const SSFontMetrics* metrics, uint32_t anchor_y, uint32_t align_x, uint8_t mask,
  SSLayer* layers, uint64_t capacity, uint64_t* count
) noexcept {
  ss_try(-1,
    auto* target = slipstream::require_atlas(atlas);
    if(!color) throw std::invalid_argument("text color is null");
    if(!metrics) throw std::invalid_argument("font metrics are null");
    if(!count) throw std::invalid_argument("text layer count output is null");
    if(codepoint_count > SIZE_MAX) throw std::invalid_argument("text codepoint count is too large");

    const slughorn::FontMetrics native_metrics{
      metrics->units_per_em, metrics->cap_height_ratio, metrics->x_height_ratio,
      metrics->ascender_ratio, metrics->descender_ratio
    };
    const auto laid_out = slipstream::layout_text(
      *target, codepoints, static_cast<size_t>(codepoint_count), font_size, x, y,
      {color->r, color->g, color->b, color->a}, native_metrics,
      anchor_y, align_x, mask);
    *count = laid_out.size();
    if(!layers) return 0;
    if(capacity < laid_out.size()) throw std::invalid_argument("text layer capacity is too small");
    for(size_t i = 0; i < laid_out.size(); ++i) layers[i] = layer_to_c(laid_out[i]);
    return 0;
  );
}
