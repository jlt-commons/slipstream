#include "reactive_renderer.hpp"

#include <cmath>
#include <stdexcept>

namespace slipstream::reactive {

PaintData build_paint_data(const slughorn::Atlas& atlas,
                           uint32_t gradient_id, uint32_t pattern_id) {
  if(gradient_id > 0 && pattern_id > 0)
    throw std::invalid_argument("a layer cannot have both gradient and pattern paint");

  PaintData data;
  if(pattern_id > 0) {
    const auto& patterns = atlas.getPatterns();
    if(pattern_id > patterns.size())
      throw std::invalid_argument("unknown pattern paint reference");
    const auto& pattern = patterns[pattern_id - 1];
    const auto tile = atlas.getShape(pattern.tile);
    if(!tile) throw std::invalid_argument("pattern tile shape is unknown");
    if(pattern.cellWidth == 0 || pattern.cellHeight == 0)
      throw std::invalid_argument("pattern cell dimensions must be nonzero");

    const auto& m = pattern.transform;
    data.kind = 2;
    data.meta = {-static_cast<float>(pattern_id), m.xx, m.yx, m.xy};
    data.transform = {m.yy, m.dx, m.dy,
      (pattern.repeatX == slughorn::PatternInfo::Repeat::Reflect ? 1.0f : 0.0f) +
      (pattern.repeatY == slughorn::PatternInfo::Repeat::Reflect ? 2.0f : 0.0f)};
    data.cell = {pattern.cellX, pattern.cellY, pattern.cellWidth, pattern.cellHeight};
    data.band_transform = {tile->bandScaleX, tile->bandScaleY,
                           tile->bandOffsetX, tile->bandOffsetY};
    data.shape_data = {static_cast<float>(tile->bandTexX), static_cast<float>(tile->bandTexY),
                       static_cast<float>(tile->bandMaxX), static_cast<float>(tile->bandMaxY)};
    return data;
  }

  if(gradient_id == 0) return data;
  const auto& gradients = atlas.getGradients();
  if(gradient_id > gradients.size())
    throw std::invalid_argument("unknown gradient paint reference");
  const auto& gradient = gradients[gradient_id - 1];
  const auto& m = gradient.transform;
  data.kind = 1;
  data.gradient_count = static_cast<int>(gradients.size());
  data.meta[0] = static_cast<float>(gradient_id);

  using Type = slughorn::GradientInfo::Type;
  if(gradient.type == Type::Radial) {
    const float delta = m.xx - gradient.innerRadius;
    const float inverse = delta > 1e-6f ? 1.0f / delta : 0.0f;
    data.meta[1] = m.dx;
    data.meta[2] = m.dy;
    data.meta[3] = gradient.innerRadius * inverse;
    data.transform = {inverse, 0.0f, 0.0f, inverse};
  } else if(gradient.type == Type::AffineRadial) {
    float b00 = m.xx, b01 = m.xy, b10 = m.yx, b11 = m.yy;
    if(b11 < 0.0f) {
      b00 = -b00; b01 = -b01; b10 = -b10; b11 = -b11;
    }
    data.meta[1] = m.dx;
    data.meta[2] = m.dy;
    data.meta[3] = gradient.innerRadius;
    data.transform = {b00, b10, b01, b11};
  } else if(gradient.type == Type::Sweep) {
    const float inverse = m.xy > 1e-6f ? 1.0f / m.xy : 0.0f;
    data.transform = {m.dx, m.dy, m.xx, -inverse};
  } else {
    data.transform = {m.xx, m.xy, m.dx, 0.0f};
  }
  return data;
}

}
