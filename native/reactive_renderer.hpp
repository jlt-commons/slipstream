#pragma once

#include <glad/glad.h>
#include <GLFW/glfw3.h>

#include <array>

#include "slughorn/slughorn.hpp"

namespace slipstream::reactive {

struct Vertex {
  float position[3];
  float color[4];
  float emCoord[4];
  float bandTransform[4];
  float shapeData[4];
  float effectId;
};

struct PaintData {
  int kind = 0;
  int gradient_count = 0;
  std::array<float, 4> meta{};
  std::array<float, 4> transform{};
  std::array<float, 4> cell{};
  std::array<float, 4> band_transform{};
  std::array<float, 4> shape_data{};
};

const char* paint_vertex_source();
const char* paint_fragment_source();
PaintData build_paint_data(const slughorn::Atlas& atlas,
                           uint32_t gradient_id, uint32_t pattern_id);
GLuint compile_shader(GLenum type, const char* source);
GLuint link_program(GLuint vertex, GLuint fragment);
GLuint upload_texture(const slughorn::Atlas::TextureData& texture);
void ortho_matrix(float left, float right, float bottom, float top,
                  float near_value, float far_value, float out[16]);

}
