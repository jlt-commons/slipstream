#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

#include "slughorn/canvas.hpp"

namespace {

slughorn::canvas::Path* require_path(void* handle) {
  if(!handle) throw std::invalid_argument("path handle is null");
  return static_cast<slughorn::canvas::Path*>(handle);
}

const slughorn::canvas::Path* require_path(const void* handle) {
  if(!handle) throw std::invalid_argument("path handle is null");
  return static_cast<const slughorn::canvas::Path*>(handle);
}

const float* require_values(const float* values, uint64_t count, uint64_t expected) {
  if(count != expected) throw std::invalid_argument("wrong path command argument count");
  if(expected > 0 && !values) throw std::invalid_argument("path command values are null");
  return values;
}

slughorn::Matrix matrix_from_c(const float* values) {
  if(!values) return {};
  return {values[0], values[1], values[2], values[3], values[4], values[5]};
}

}

void* ss_path_new(void) noexcept {
  ss_try(nullptr, return new slughorn::canvas::Path(););
}

int ss_path_destroy(void* path) noexcept {
  ss_try(-1, delete require_path(path); return 0;);
}

int ss_path_command(
  void* path, uint32_t command, const float* values, uint64_t count
) noexcept {
  ss_try(-1,
    auto* p = require_path(path);
    switch(command) {
      case 0: require_values(values, count, 0); p->clear(); break;
      case 1: require_values(values, count, 0); p->save(); break;
      case 2: require_values(values, count, 0); p->restore(); break;
      case 3: require_values(values, count, 0); p->resetTransform(); break;
      case 4: require_values(values, count, 6); p->setTransform(matrix_from_c(values)); break;
      case 5: require_values(values, count, 6); p->transform(matrix_from_c(values)); break;
      case 6: require_values(values, count, 2); p->translate(values[0], values[1]); break;
      case 7: require_values(values, count, 1); p->rotate(values[0]); break;
      case 8: require_values(values, count, 2); p->scale(values[0], values[1]); break;
      case 9: require_values(values, count, 2); p->moveTo(values[0], values[1]); break;
      case 10: require_values(values, count, 2); p->lineTo(values[0], values[1]); break;
      case 11: require_values(values, count, 4); p->quadTo(values[0], values[1], values[2], values[3]); break;
      case 12: require_values(values, count, 6); p->bezierTo(values[0], values[1], values[2], values[3], values[4], values[5]); break;
      case 13: require_values(values, count, 0); p->closePath(); break;
      case 14: require_values(values, count, 4); p->rect(values[0], values[1], values[2], values[3]); break;
      case 15: require_values(values, count, 5); p->roundedRect(values[0], values[1], values[2], values[3], values[4]); break;
      case 16: require_values(values, count, 3); p->circle(values[0], values[1], values[2]); break;
      case 17: require_values(values, count, 4); p->ellipse(values[0], values[1], values[2], values[3]); break;
      case 18: require_values(values, count, 6); p->arc(values[0], values[1], values[2], values[3], values[4], values[5] != 0); break;
      case 19: require_values(values, count, 5); p->arcTo(values[0], values[1], values[2], values[3], values[4]); break;
      default: throw std::invalid_argument("unknown path command");
    }
    return 0;
  );
}

int ss_path_add(void* path, const void* source, const float* matrix) noexcept {
  ss_try(-1,
    auto* target = require_path(path);
    const auto* input = require_path(source);
    if(matrix) target->addPath(*input, matrix_from_c(matrix));
    else target->addPath(*input);
    return 0;
  );
}

int ss_path_sample(const void* path, float t, SSPathSample* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("path sample output is null");
    const auto sample = require_path(path)->sample(t);
    *out = {sample.x, sample.y, sample.angle, sample.arcLength};
    return 0;
  );
}

int ss_path_arc_length(const void* path, float* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("arc length output is null");
    *out = require_path(path)->arcLength();
    return 0;
  );
}

int ss_path_stroke(
  void* path, float width, bool clockwise, uint32_t join, uint32_t cap,
  float miter_limit
) noexcept {
  ss_try(-1,
    if(width <= 0) throw std::invalid_argument("stroke width must be greater than zero");
    if(join > 2) throw std::invalid_argument("unknown stroke join");
    if(cap > 2) throw std::invalid_argument("unknown stroke cap");
    if(!require_path(path)->strokePath(
        width, clockwise,
        static_cast<slughorn::canvas::LineJoin>(join),
        static_cast<slughorn::canvas::LineCap>(cap), miter_limit)) {
      throw std::runtime_error("cannot stroke an empty path");
    }
    return 0;
  );
}
