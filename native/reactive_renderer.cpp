#include "reactive_renderer.hpp"

#include "slipstream_internal.hpp"
#include "slipstream_shim.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

using slipstream::reactive::Vertex;

const char* vertex_source = R"(
#version 330 core
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec4 a_emCoord;
layout(location = 3) in vec4 a_bandTransform;
layout(location = 4) in vec4 a_shapeData;
layout(location = 5) in float a_effectId;
uniform mat4 u_mvp;
uniform vec3 u_transform;
uniform float u_scale;
uniform vec4 u_color;
out vec2 v_emCoord;
out vec4 v_color;
flat out vec4 v_bandTransform;
flat out vec4 v_shapeData;
void main() {
  v_emCoord = a_emCoord.xy;
  v_color = a_color * u_color;
  v_bandTransform = a_bandTransform;
  v_shapeData = a_shapeData;
  vec3 position = a_position * u_scale + u_transform;
  gl_Position = u_mvp * vec4(position, 1.0);
}
)";

const char* fragment_source = R"(
#version 330 core
in vec2 v_emCoord;
in vec4 v_color;
flat in vec4 v_bandTransform;
flat in vec4 v_shapeData;
uniform sampler2D u_curveTexture;
uniform usampler2D u_bandTexture;
uniform int u_textureWidth;
out vec4 fragColor;
#define SLUG_INDIRECTION_SIZE 32
uint slug_CalcRootCode(float y1, float y2, float y3) {
  uint i1 = floatBitsToUint(y1) >> 31u;
  uint i2 = floatBitsToUint(y2) >> 30u;
  uint i3 = floatBitsToUint(y3) >> 29u;
  uint shift = (i2 & 2u) | (i1 & ~2u);
  shift = (i3 & 4u) | (shift & ~4u);
  return ((0x2E74u >> shift) & 0x0101u);
}
vec2 slug_SolveRoots(float a, float b, float c) {
  float disc = b * b - a * c;
  float d = sqrt(max(disc, 0.0));
  float q = b >= 0.0 ? b + d : b - d;
  float tFar = q / a;
  float tNear = disc > 0.0 ? c / q : tFar;
  return b >= 0.0 ? vec2(tNear, tFar) : vec2(tFar, tNear);
}
vec2 slug_SolveHorizPoly(vec4 p12, vec2 p3) {
  vec2 a = p12.xy - p12.zw * 2.0 + p3;
  vec2 b = p12.xy - p12.zw;
  float rb = 0.5 / b.y;
  vec2 t = slug_SolveRoots(a.y, b.y, p12.y);
  float t1 = t.x;
  float t2 = t.y;
  if(abs(a.y) < 1.0 / 65536.0) { t1 = p12.y * rb; t2 = t1; }
  return vec2((a.x * t1 - b.x * 2.0) * t1 + p12.x,
              (a.x * t2 - b.x * 2.0) * t2 + p12.x);
}
vec2 slug_SolveVertPoly(vec4 p12, vec2 p3) {
  vec2 a = p12.xy - p12.zw * 2.0 + p3;
  vec2 b = p12.xy - p12.zw;
  float rb = 0.5 / b.x;
  vec2 t = slug_SolveRoots(a.x, b.x, p12.x);
  float t1 = t.x;
  float t2 = t.y;
  if(abs(a.x) < 1.0 / 65536.0) { t1 = p12.x * rb; t2 = t1; }
  return vec2((a.y * t1 - b.y * 2.0) * t1 + p12.y,
              (a.y * t2 - b.y * 2.0) * t2 + p12.y);
}
ivec2 slug_CalcBandLoc(ivec2 glyphLoc, uint offset) {
  ivec2 bandLoc = ivec2(glyphLoc.x + int(offset), glyphLoc.y);
  bandLoc.y += bandLoc.x / u_textureWidth;
  bandLoc.x %= u_textureWidth;
  return bandLoc;
}
ivec2 slug_CalcCurveLoc(ivec2 curveLoc, int offset) {
  ivec2 loc = ivec2(curveLoc.x + offset, curveLoc.y);
  loc.y += loc.x / u_textureWidth;
  loc.x %= u_textureWidth;
  return loc;
}
float slug_CalcCoverage(float xcov, float ycov, float xwgt, float ywgt) {
  float coverage = max(abs(xcov * xwgt + ycov * ywgt) /
                       max(xwgt + ywgt, 1.0 / 65536.0),
                       min(abs(xcov), abs(ycov)));
  return clamp(coverage, 0.0, 1.0);
}
float slug_Render(vec2 renderCoord, vec4 bandTransform, ivec2 glyphLoc, ivec2 bandMax) {
  vec2 emsPerPixel = fwidth(renderCoord);
  vec2 pixelsPerEm = 1.0 / emsPerPixel;
  int qY = clamp(int(renderCoord.y * bandTransform.y + bandTransform.w), 0, SLUG_INDIRECTION_SIZE - 1);
  int qX = clamp(int(renderCoord.x * bandTransform.x + bandTransform.z), 0, SLUG_INDIRECTION_SIZE - 1);
  int bandY = int(texelFetch(u_bandTexture, ivec2(glyphLoc.x + qY, glyphLoc.y), 0).r);
  int bandX = int(texelFetch(u_bandTexture, ivec2(glyphLoc.x + SLUG_INDIRECTION_SIZE + qX, glyphLoc.y), 0).r);
  float xcov = 0.0, xwgt = 0.0;
  uvec2 hbandData = texelFetch(u_bandTexture, ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandY, glyphLoc.y), 0).xy;
  for(int ci = 0; ci < int(hbandData.x); ci++) {
    ivec2 hbandLoc = slug_CalcBandLoc(glyphLoc, hbandData.y + uint(ci));
    ivec2 curveLoc = ivec2(texelFetch(u_bandTexture, hbandLoc, 0).xy);
    vec4 p12 = texelFetch(u_curveTexture, curveLoc, 0) - vec4(renderCoord, renderCoord);
    vec2 p3 = texelFetch(u_curveTexture, slug_CalcCurveLoc(curveLoc, 1), 0).xy - renderCoord;
    if(max(max(p12.x, p12.z), p3.x) * pixelsPerEm.x < -0.5) break;
    uint code = slug_CalcRootCode(p12.y, p12.w, p3.y);
    if(code != 0u) {
      vec2 r = slug_SolveHorizPoly(p12, p3) * pixelsPerEm.x;
      if((code & 1u) != 0u) { xcov += clamp(r.x + 0.5, 0.0, 1.0); xwgt = max(xwgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0)); }
      if(code > 1u) { xcov -= clamp(r.y + 0.5, 0.0, 1.0); xwgt = max(xwgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0)); }
    }
  }
  float ycov = 0.0, ywgt = 0.0;
  uvec2 vbandData = texelFetch(u_bandTexture, ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandMax.y + 1 + bandX, glyphLoc.y), 0).xy;
  for(int ci = 0; ci < int(vbandData.x); ci++) {
    ivec2 vbandLoc = slug_CalcBandLoc(glyphLoc, vbandData.y + uint(ci));
    ivec2 curveLoc = ivec2(texelFetch(u_bandTexture, vbandLoc, 0).xy);
    vec4 p12 = texelFetch(u_curveTexture, curveLoc, 0) - vec4(renderCoord, renderCoord);
    vec2 p3 = texelFetch(u_curveTexture, slug_CalcCurveLoc(curveLoc, 1), 0).xy - renderCoord;
    if(max(max(p12.y, p12.w), p3.y) * pixelsPerEm.y < -0.5) break;
    uint code = slug_CalcRootCode(p12.x, p12.z, p3.x);
    if(code != 0u) {
      vec2 r = slug_SolveVertPoly(p12, p3) * pixelsPerEm.y;
      if((code & 1u) != 0u) { ycov -= clamp(r.x + 0.5, 0.0, 1.0); ywgt = max(ywgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0)); }
      if(code > 1u) { ycov += clamp(r.y + 0.5, 0.0, 1.0); ywgt = max(ywgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0)); }
    }
  }
  return slug_CalcCoverage(xcov, ycov, xwgt, ywgt);
}
void main() {
  float fill = slug_Render(v_emCoord, v_bandTransform,
                           ivec2(v_shapeData.xy), ivec2(v_shapeData.zw));
  if(fill < 0.001) discard;
  fragColor = vec4(v_color.rgb, fill * v_color.a);
}
)";

struct GLContext;

struct GLResource {
  GLContext* owner = nullptr;
  GLuint curve = 0;
  GLuint band = 0;
  GLuint vao = 0;
  GLuint vbo = 0;
  GLuint ebo = 0;
  GLsizei index_count = 0;
  uint32_t texture_width = 0;

  ~GLResource() {
    if(vao) glDeleteVertexArrays(1, &vao);
    if(vbo) glDeleteBuffers(1, &vbo);
    if(ebo) glDeleteBuffers(1, &ebo);
    if(curve) glDeleteTextures(1, &curve);
    if(band) glDeleteTextures(1, &band);
  }
};

struct GLContext {
  GLFWwindow* window = nullptr;
  GLuint vertex = 0;
  GLuint fragment = 0;
  GLuint program = 0;
  bool has_bounds = false;
  float bounds[4] = {0, 1, 0, 1};
  std::unordered_set<GLResource*> resources;

  ~GLContext() {
    if(window) glfwMakeContextCurrent(window);
    for(auto* resource : resources) delete resource;
    if(program) glDeleteProgram(program);
    if(vertex) glDeleteShader(vertex);
    if(fragment) glDeleteShader(fragment);
    if(window) glfwDestroyWindow(window);
  }
};

std::mutex registry_mutex;
std::unordered_set<GLContext*> contexts;
std::unordered_set<GLResource*> resources;
size_t glfw_users = 0;

void glfw_error(int code, const char* message) {
  std::fprintf(stderr, "GLFW error %d: %s\n", code, message);
}

void release_glfw() {
  std::lock_guard lock(registry_mutex);
  if(glfw_users > 0 && --glfw_users == 0) glfwTerminate();
}

GLContext* require_context(void* handle) {
  if(!handle) throw std::invalid_argument("GL context handle is null");
  auto* context = static_cast<GLContext*>(handle);
  std::lock_guard lock(registry_mutex);
  if(!contexts.contains(context)) throw std::invalid_argument("GL context is destroyed or invalid");
  return context;
}

GLResource* require_resource(GLContext* context, void* handle) {
  if(!handle) throw std::invalid_argument("GL resource handle is null");
  auto* resource = static_cast<GLResource*>(handle);
  std::lock_guard lock(registry_mutex);
  if(!resources.contains(resource)) throw std::invalid_argument("GL resource is released or invalid");
  if(resource->owner != context)
    throw std::invalid_argument("GL resource belongs to a different GL context");
  return resource;
}

void destroy_context(GLContext* context) {
  for(auto* resource : context->resources) {
    std::lock_guard lock(registry_mutex);
    resources.erase(resource);
  }
  delete context;
  release_glfw();
}

void initialize_context(GLContext* context) {
  glfwMakeContextCurrent(context->window);
  glfwSwapInterval(0);
  if(!gladLoadGLLoader(reinterpret_cast<GLADloadproc>(glfwGetProcAddress)))
    throw std::runtime_error("gladLoadGLLoader failed");
  context->vertex = slipstream::reactive::compile_shader(GL_VERTEX_SHADER, vertex_source);
  context->fragment = slipstream::reactive::compile_shader(GL_FRAGMENT_SHADER, fragment_source);
  context->program = slipstream::reactive::link_program(context->vertex, context->fragment);
  glUseProgram(context->program);
  glUniform1i(glGetUniformLocation(context->program, "u_curveTexture"), 0);
  glUniform1i(glGetUniformLocation(context->program, "u_bandTexture"), 1);
  glEnable(GL_BLEND);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  glClearColor(0.15f, 0.15f, 0.15f, 1.0f);
}

void validate_commands(GLContext* context, const SSGLDrawCommand* commands, uint64_t count) {
  if(count > 0 && !commands) throw std::invalid_argument("GL draw commands are null");
  for(uint64_t i = 0; i < count; ++i) require_resource(context, commands[i].handle);
}

}

namespace slipstream::reactive {

GLuint compile_shader(GLenum type, const char* source) {
  GLuint shader = glCreateShader(type);
  glShaderSource(shader, 1, &source, nullptr);
  glCompileShader(shader);
  GLint ok = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
  if(!ok) {
    char log[2048];
    glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
    glDeleteShader(shader);
    throw std::runtime_error(std::string("Shader compile error:\n") + log);
  }
  return shader;
}

GLuint link_program(GLuint vertex, GLuint fragment) {
  GLuint program = glCreateProgram();
  glAttachShader(program, vertex);
  glAttachShader(program, fragment);
  glLinkProgram(program);
  GLint ok = 0;
  glGetProgramiv(program, GL_LINK_STATUS, &ok);
  if(!ok) {
    char log[2048];
    glGetProgramInfoLog(program, sizeof(log), nullptr, log);
    glDeleteProgram(program);
    throw std::runtime_error(std::string("Program link error:\n") + log);
  }
  return program;
}

GLuint upload_texture(const slughorn::Atlas::TextureData& texture) {
  GLuint result = 0;
  glGenTextures(1, &result);
  glBindTexture(GL_TEXTURE_2D, result);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  using Format = slughorn::Atlas::TextureData::Format;
  if(texture.format == Format::RGBA32F) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, texture.width, texture.height, 0,
                 GL_RGBA, GL_FLOAT, texture.bytes.data());
  } else if(texture.format == Format::RGBA16F) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, texture.width, texture.height, 0,
                 GL_RGBA, GL_HALF_FLOAT, texture.bytes.data());
  } else if(texture.format == Format::RG16UI) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16UI, texture.width, texture.height, 0,
                 GL_RG_INTEGER, GL_UNSIGNED_SHORT, texture.bytes.data());
  } else if(texture.format == Format::RGBA16UI) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16UI, texture.width, texture.height, 0,
                 GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, texture.bytes.data());
  } else {
    glDeleteTextures(1, &result);
    throw std::invalid_argument("unsupported Slug texture format");
  }
  glBindTexture(GL_TEXTURE_2D, 0);
  return result;
}

void ortho_matrix(float left, float right, float bottom, float top,
                  float near_value, float far_value, float out[16]) {
  std::memset(out, 0, 16 * sizeof(float));
  out[0] = 2.0f / (right - left);
  out[5] = 2.0f / (top - bottom);
  out[10] = -2.0f / (far_value - near_value);
  out[12] = -(right + left) / (right - left);
  out[13] = -(top + bottom) / (top - bottom);
  out[14] = -(far_value + near_value) / (far_value - near_value);
  out[15] = 1.0f;
}

}

void* ss_gl_context_create(const SSGLContextOptions* options) noexcept {
  ss_try(nullptr,
    int width = options && options->width > 0 ? options->width : 400;
    int height = options && options->height > 0 ? options->height : 300;
    {
      std::lock_guard lock(registry_mutex);
      if(glfw_users == 0) {
        glfwSetErrorCallback(glfw_error);
        if(!glfwInit()) throw std::runtime_error("glfwInit failed");
      }
      ++glfw_users;
    }
    std::unique_ptr<GLContext> context(new GLContext);
    try {
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
      glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
      glfwWindowHint(GLFW_VISIBLE, options && !options->visible ? GLFW_FALSE : GLFW_TRUE);
#ifdef __APPLE__
      glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
#endif
      const char* title = options && options->title ? options->title : "glimmer";
      context->window = glfwCreateWindow(width, height, title, nullptr, nullptr);
      if(!context->window) throw std::runtime_error("glfwCreateWindow failed");
      if(options && options->has_bounds) {
        context->has_bounds = true;
        std::copy(options->bounds, options->bounds + 4, context->bounds);
      }
      initialize_context(context.get());
    } catch(...) {
      destroy_context(context.get());
      context.release();
      throw;
    }
    {
      std::lock_guard lock(registry_mutex);
      contexts.insert(context.get());
    }
    return context.release();
  );
}

int ss_gl_context_destroy(void* handle) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    {
      std::lock_guard lock(registry_mutex);
      contexts.erase(context);
    }
    destroy_context(context);
    return 0;
  );
}

int ss_gl_poll(void* handle) noexcept {
  ss_try(1,
    require_context(handle);
    glfwPollEvents();
    return 0;
  );
}

bool ss_gl_should_close(void* handle) noexcept {
  ss_try(false,
    auto* context = require_context(handle);
    return glfwWindowShouldClose(context->window);
  );
}

void* ss_gl_upload(void* handle, void* atlasHandle, const SSLayer* root) noexcept {
  ss_try(nullptr,
    auto* context = require_context(handle);
    auto* atlas = slipstream::require_atlas(atlasHandle);
    if(!root) throw std::invalid_argument("GL upload root layer is null");
    glfwMakeContextCurrent(context->window);
    SSLayer neutral = *root;
    neutral.color = {1, 1, 1, 1};
    neutral.transform = {0, 0, 0};
    neutral.scale = 1;
    void* meshHandle = ss_mesh_new(atlasHandle, &neutral, 1);
    if(!meshHandle) throw std::runtime_error(slipstream::last_error);
    std::unique_ptr<GLResource> resource(new GLResource);
    resource->owner = context;
    try {
      SSMeshDescriptor mesh = {};
      if(ss_mesh_descriptor(meshHandle, &mesh) != 0)
        throw std::runtime_error(slipstream::last_error);
      resource->curve = slipstream::reactive::upload_texture(atlas->getCurveTextureData());
      resource->band = slipstream::reactive::upload_texture(atlas->getBandTextureData());
      resource->texture_width = atlas->getBandTextureData().width;
      resource->index_count = static_cast<GLsizei>(mesh.index_count);
      glGenVertexArrays(1, &resource->vao);
      glGenBuffers(1, &resource->vbo);
      glGenBuffers(1, &resource->ebo);
      glBindVertexArray(resource->vao);
      glBindBuffer(GL_ARRAY_BUFFER, resource->vbo);
      glBufferData(GL_ARRAY_BUFFER, mesh.vertex_count * sizeof(Vertex), mesh.vertices, GL_STATIC_DRAW);
      glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource->ebo);
      glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.index_count * sizeof(uint16_t), mesh.indices, GL_STATIC_DRAW);
      const GLsizei stride = sizeof(Vertex);
      glEnableVertexAttribArray(0);
      glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Vertex, position)));
      glEnableVertexAttribArray(1);
      glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Vertex, color)));
      glEnableVertexAttribArray(2);
      glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Vertex, emCoord)));
      glEnableVertexAttribArray(3);
      glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Vertex, bandTransform)));
      glEnableVertexAttribArray(4);
      glVertexAttribPointer(4, 4, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Vertex, shapeData)));
      glEnableVertexAttribArray(5);
      glVertexAttribPointer(5, 1, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Vertex, effectId)));
      glBindVertexArray(0);
      if(ss_mesh_destroy(meshHandle) != 0) throw std::runtime_error(slipstream::last_error);
    } catch(...) {
      ss_mesh_destroy(meshHandle);
      throw;
    }
    {
      std::lock_guard lock(registry_mutex);
      context->resources.insert(resource.get());
      resources.insert(resource.get());
    }
    return resource.release();
  );
}

int ss_gl_release(void* handle, void* resourceHandle) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    auto* resource = require_resource(context, resourceHandle);
    glfwMakeContextCurrent(context->window);
    {
      std::lock_guard lock(registry_mutex);
      context->resources.erase(resource);
      resources.erase(resource);
    }
    delete resource;
    return 0;
  );
}

int ss_gl_render(void* handle, const SSGLDrawCommand* commands, uint64_t count) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    validate_commands(context, commands, count);
    glfwMakeContextCurrent(context->window);
    int width = 0;
    int height = 0;
    glfwGetFramebufferSize(context->window, &width, &height);
    glViewport(0, 0, width, height);
    glClear(GL_COLOR_BUFFER_BIT);
    float aspect = height > 0 ? static_cast<float>(width) / height : 1.0f;
    float bounds[4] = {0, aspect, 0, 1};
    if(context->has_bounds) std::copy(context->bounds, context->bounds + 4, bounds);
    if(bounds[0] == bounds[1] || bounds[2] == bounds[3])
      throw std::invalid_argument("GL orthographic bounds must have nonzero area");
    float projection[16];
    slipstream::reactive::ortho_matrix(bounds[0], bounds[1], bounds[2], bounds[3],
                                       -100.0f, 100.0f, projection);
    glUseProgram(context->program);
    glUniformMatrix4fv(glGetUniformLocation(context->program, "u_mvp"), 1, GL_FALSE, projection);
    for(uint64_t i = 0; i < count; ++i) {
      const auto& command = commands[i];
      if(!command.visible) continue;
      auto* resource = require_resource(context, command.handle);
      glUniform3f(glGetUniformLocation(context->program, "u_transform"),
                  command.transform.x, command.transform.y, command.transform.z);
      glUniform1f(glGetUniformLocation(context->program, "u_scale"), command.scale);
      glUniform4f(glGetUniformLocation(context->program, "u_color"),
                  command.color.r, command.color.g, command.color.b, command.color.a);
      glUniform1i(glGetUniformLocation(context->program, "u_textureWidth"), resource->texture_width);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, resource->curve);
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_2D, resource->band);
      glBindVertexArray(resource->vao);
      glDrawElements(GL_TRIANGLES, resource->index_count, GL_UNSIGNED_SHORT, nullptr);
    }
    glBindVertexArray(0);
    glUseProgram(0);
    return 0;
  );
}

int ss_gl_swap(void* handle) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    glfwSwapBuffers(context->window);
    return 0;
  );
}
