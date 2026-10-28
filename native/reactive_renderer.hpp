#pragma once

#include <glad/glad.h>
#include <GLFW/glfw3.h>

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

GLuint compile_shader(GLenum type, const char* source);
GLuint link_program(GLuint vertex, GLuint fragment);
GLuint upload_texture(const slughorn::Atlas::TextureData& texture);
void ortho_matrix(float left, float right, float bottom, float top,
                  float near_value, float far_value, float out[16]);

}
