#pragma once

#include <glad/glad.h>

#include <array>
#include <cstdint>
#include <functional>
#include <memory>

namespace slipstream::reactive {

struct CompositeMask {
  int type = -1;
  bool invert = false;
  std::array<float, 6> params{};
  GLuint texture = 0;
  int sdf_type = 0;
  std::array<float, 4> sdf_rect{};
  std::array<float, 4> sdf_frame{};
  std::array<float, 3> transform{};
  float scale = 1;
  float opacity = 1;
};

class Compositor {
public:
  Compositor();
  ~Compositor();

  Compositor(const Compositor&) = delete;
  Compositor& operator=(const Compositor&) = delete;

  void begin_frame(int width, int height, const float bounds[4]);
  void draw(const std::function<void()>& draw_geometry,
            uint8_t draw_mode, uint8_t blend_mode);
  void begin_group(const CompositeMask& mask);
  void end_group(uint8_t blend_mode);
  void end_frame();
  void restore();

private:
  struct Impl;
  std::unique_ptr<Impl> impl;
};

}
