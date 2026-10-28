#include "reactive_renderer.hpp"

#include "compositor.hpp"
#include "slipstream_internal.hpp"
#include "slipstream_shim.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {

using slipstream::reactive::Vertex;

struct GLContext;

struct GLPaint {
  GLContext* owner = nullptr;
  GLuint texture = 0;
  slipstream::reactive::PaintData data;

  ~GLPaint() {
    if(texture) glDeleteTextures(1, &texture);
  }
};

struct GLResource {
  GLContext* owner = nullptr;
  GLPaint* paint = nullptr;
  GLuint curve = 0;
  GLuint band = 0;
  GLuint vao = 0;
  GLuint vbo = 0;
  GLuint ebo = 0;
  GLsizei index_count = 0;
  uint32_t texture_width = 0;
  GLuint sdf = 0;
  int sdf_type = 0;
  std::array<float, 4> sdf_rect{};
  std::array<float, 4> sdf_frame{};

  ~GLResource() {
    if(vao) glDeleteVertexArrays(1, &vao);
    if(vbo) glDeleteBuffers(1, &vbo);
    if(ebo) glDeleteBuffers(1, &ebo);
    if(curve) glDeleteTextures(1, &curve);
    if(band) glDeleteTextures(1, &band);
    if(sdf) glDeleteTextures(1, &sdf);
    delete paint;
  }
};

struct GLProgram {
  GLuint vertex = 0;
  GLuint fragment = 0;
  GLuint id = 0;
};

struct NativeUniform {
  std::string name;
  uint32_t type = 0;
  uint32_t components = 0;
  std::array<float, 16> values{};
  std::array<int32_t, 16> int_values{};
  std::array<uint32_t, 16> uint_values{};
};

struct GLEffect {
  GLContext* owner = nullptr;
  GLProgram* program = nullptr;
  bool requires_sdf = false;
  std::vector<NativeUniform> uniforms;
};

void destroy_program(GLProgram& program) {
  if(program.id) glDeleteProgram(program.id);
  if(program.vertex) glDeleteShader(program.vertex);
  if(program.fragment) glDeleteShader(program.fragment);
  program = {};
}

struct GLContext {
  GLFWwindow* window = nullptr;
  GLProgram paint_program;
  std::unordered_map<std::string, std::unique_ptr<GLProgram>> effect_programs;
  std::unordered_map<std::string, std::unique_ptr<GLEffect>> effects;
  std::unique_ptr<slipstream::reactive::Compositor> compositor;
  GLuint dummy_gradient = 0;
  GLuint test_fbo = 0;
  GLuint test_texture = 0;
  uint32_t shader_compilations = 0;
  uint32_t resource_uploads = 0;
  uint32_t paint_texture_uploads = 0;
  uint32_t draw_commands = 0;
  uint32_t geometry_commands = 0;
  uint32_t group_commands = 0;
  float effect_param = 0;
  bool has_bounds = false;
  float bounds[4] = {0, 1, 0, 1};
  slipstream::reactive::InputState input;
  std::unordered_set<GLResource*> resources;

  ~GLContext() {
    if(window) glfwMakeContextCurrent(window);
    for(auto* resource : resources) delete resource;
    effects.clear();
    for(auto& [key, program] : effect_programs) destroy_program(*program);
    effect_programs.clear();
    compositor.reset();
    destroy_program(paint_program);
    if(dummy_gradient) glDeleteTextures(1, &dummy_gradient);
    if(test_fbo) glDeleteFramebuffers(1, &test_fbo);
    if(test_texture) glDeleteTextures(1, &test_texture);
    if(window) glfwDestroyWindow(window);
  }
};

GLContext* window_context(GLFWwindow* window) {
  return static_cast<GLContext*>(glfwGetWindowUserPointer(window));
}

void cursor_position(GLFWwindow* window, double x, double y) {
  auto& input = window_context(window)->input;
  input.x = x;
  input.y = y;
}

void mouse_button(GLFWwindow* window, int button, int action, int) {
  auto& input = window_context(window)->input;
  uint8_t* state = nullptr;
  switch(button) {
    case GLFW_MOUSE_BUTTON_LEFT: state = &input.left; break;
    case GLFW_MOUSE_BUTTON_RIGHT: state = &input.right; break;
    case GLFW_MOUSE_BUTTON_MIDDLE: state = &input.middle; break;
    default: return;
  }
  *state = action == GLFW_RELEASE ? 0 : 1;
}

void scroll(GLFWwindow* window, double x, double y) {
  auto& input = window_context(window)->input;
  input.scroll_x += x;
  input.scroll_y += y;
}

void key(GLFWwindow* window, int key_code, int, int action, int) {
  auto& input = window_context(window)->input;
  if(key_code == GLFW_KEY_SPACE) input.space = action == GLFW_RELEASE ? 0 : 1;
  if(key_code == GLFW_KEY_R) input.r = action == GLFW_RELEASE ? 0 : 1;
}

std::mutex registry_mutex;
std::unordered_set<GLContext*> contexts;
std::unordered_set<GLResource*> resources;
std::unordered_set<GLPaint*> paints;
std::unordered_set<GLEffect*> effects;
size_t glfw_users = 0;
thread_local int64_t gl_error_command = -1;

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

GLPaint* require_paint(GLContext* context, void* handle) {
  if(!handle) throw std::invalid_argument("paint reference is null");
  auto* paint = static_cast<GLPaint*>(handle);
  std::lock_guard lock(registry_mutex);
  if(!paints.contains(paint)) throw std::invalid_argument("paint reference is released or invalid");
  if(paint->owner != context)
    throw std::invalid_argument("paint reference belongs to a different GL context");
  return paint;
}

GLEffect* require_effect(GLContext* context, void* handle) {
  if(!handle) throw std::invalid_argument("effect reference is null");
  auto* effect = static_cast<GLEffect*>(handle);
  std::lock_guard lock(registry_mutex);
  if(!effects.contains(effect)) throw std::invalid_argument("effect reference is released or invalid");
  if(effect->owner != context)
    throw std::invalid_argument("effect reference belongs to a different GL context");
  return effect;
}

void destroy_context(GLContext* context) {
  for(auto* resource : context->resources) {
    std::lock_guard lock(registry_mutex);
    resources.erase(resource);
    if(resource->paint) paints.erase(resource->paint);
  }
  for(const auto& [key, effect] : context->effects) {
    std::lock_guard lock(registry_mutex);
    effects.erase(effect.get());
  }
  delete context;
  release_glfw();
}

void initialize_context(GLContext* context) {
  glfwMakeContextCurrent(context->window);
  glfwSwapInterval(0);
  if(!gladLoadGLLoader(reinterpret_cast<GLADloadproc>(glfwGetProcAddress)))
    throw std::runtime_error("gladLoadGLLoader failed");
  const uint8_t white[4] = {255, 255, 255, 255};
  glGenTextures(1, &context->dummy_gradient);
  glBindTexture(GL_TEXTURE_2D, context->dummy_gradient);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
  glBindTexture(GL_TEXTURE_2D, 0);
  context->paint_program.vertex = slipstream::reactive::compile_shader(
    GL_VERTEX_SHADER, slipstream::reactive::paint_vertex_source());
  ++context->shader_compilations;
  context->paint_program.fragment = slipstream::reactive::compile_shader(
    GL_FRAGMENT_SHADER, slipstream::reactive::paint_fragment_source());
  ++context->shader_compilations;
  context->paint_program.id = slipstream::reactive::link_program(
    context->paint_program.vertex, context->paint_program.fragment);
  glUseProgram(context->paint_program.id);
  glUniform1i(glGetUniformLocation(context->paint_program.id, "u_curveTexture"), 0);
  glUniform1i(glGetUniformLocation(context->paint_program.id, "u_bandTexture"), 1);
  glUniform1i(glGetUniformLocation(context->paint_program.id, "u_gradientTexture"), 2);
  context->compositor = std::make_unique<slipstream::reactive::Compositor>();
  context->shader_compilations += 4;
  glDisable(GL_BLEND);
  glClearColor(0.15f, 0.15f, 0.15f, 1.0f);
}

void validate_blend_mode(uint8_t mode) {
  if((mode > 11 && mode < 20) || mode > 30)
    throw std::invalid_argument("unknown blend mode " + std::to_string(mode));
}

void validate_commands(GLContext* context, const SSGLDrawCommand* commands, uint64_t count) {
  if(count > 0 && !commands) throw std::invalid_argument("GL draw commands are null");
  uint64_t depth = 0;
  for(uint64_t i = 0; i < count; ++i) {
    gl_error_command = static_cast<int64_t>(i);
    const auto& command = commands[i];
    if(command.kind > 2) throw std::invalid_argument("unknown GL command kind");
    validate_blend_mode(command.blend_mode);
    if(command.kind == 2) {
      if(depth == 0) throw std::invalid_argument("end-group has no matching begin-group");
      --depth;
      continue;
    }
    if(command.kind == 1) {
      ++depth;
      if(command.mask_type != 255 && command.mask_type > 8)
        throw std::invalid_argument("unknown mask type");
      if(command.mask_type == 0) {
        auto* mask = require_resource(context, command.handle);
        if(!mask->sdf) throw std::invalid_argument("SDF tile mask resource has no baked tile");
      }
      continue;
    }
    auto* resource = require_resource(context, command.handle);
    if(command.draw_mode > 3) throw std::invalid_argument("unknown draw mode");
    if(command.bleed < 0.0f) throw std::invalid_argument("draw bleed must be nonnegative");
    auto* effect = command.effect ? require_effect(context, command.effect) : nullptr;
    if(command.effect_id != 0 && !effect)
      throw std::invalid_argument("custom effects require an effect program, not the paint program");
    if(effect && effect->requires_sdf && !resource->sdf)
      throw std::invalid_argument("effect requires SDF data, but the resource has no baked tile");
    if(resource->paint && !command.paint)
      throw std::invalid_argument("draw command is missing its advanced paint reference");
    if(!resource->paint && command.paint)
      throw std::invalid_argument("flat draw command has an unexpected paint reference");
    if(resource->paint && require_paint(context, command.paint) != resource->paint)
      throw std::invalid_argument("draw command paint reference belongs to another resource");
  }
  if(depth != 0) throw std::invalid_argument("begin-group has no matching end-group");
}

void apply_effect_uniforms(GLuint program, const GLEffect* effect) {
  for(const auto& uniform : effect->uniforms) {
    const GLint location = glGetUniformLocation(program, uniform.name.c_str());
    if(location < 0) continue;
    const float* v = uniform.values.data();
    const int32_t* iv = uniform.int_values.data();
    const uint32_t* uv = uniform.uint_values.data();
    switch(uniform.type) {
      case 0: glUniform1fv(location, 1, v); break;
      case 1: glUniform2fv(location, 1, v); break;
      case 2: glUniform3fv(location, 1, v); break;
      case 3: glUniform4fv(location, 1, v); break;
      case 4: case 12: glUniform1iv(location, 1, iv); break;
      case 5: case 13: glUniform2iv(location, 1, iv); break;
      case 6: case 14: glUniform3iv(location, 1, iv); break;
      case 7: case 15: glUniform4iv(location, 1, iv); break;
      case 8: glUniform1ui(location, uv[0]); break;
      case 9: glUniform2uiv(location, 1, uv); break;
      case 10: glUniform3uiv(location, 1, uv); break;
      case 11: glUniform4uiv(location, 1, uv); break;
      case 16: glUniformMatrix3fv(location, 1, GL_FALSE, v); break;
      case 17: glUniformMatrix4fv(location, 1, GL_FALSE, v); break;
      default: throw std::invalid_argument("unknown effect uniform type");
    }
  }
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
  } else if(texture.format == Format::RGBA8) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, texture.width, texture.height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, texture.bytes.data());
  } else if(texture.format == Format::RGB32F) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, texture.width, texture.height, 0,
                 GL_RGB, GL_FLOAT, texture.bytes.data());
  } else if(texture.format == Format::R32F) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, texture.width, texture.height, 0,
                 GL_RED, GL_FLOAT, texture.bytes.data());
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
      glfwSetWindowUserPointer(context->window, context.get());
      glfwSetCursorPosCallback(context->window, cursor_position);
      glfwSetMouseButtonCallback(context->window, mouse_button);
      glfwSetScrollCallback(context->window, scroll);
      glfwSetKeyCallback(context->window, key);
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

int ss_gl_input_snapshot(void* handle, SSGLInput* out) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(!out) throw std::invalid_argument("input snapshot output is null");
    const auto& input = context->input;
    out->x = input.x;
    out->y = input.y;
    out->scroll_x = input.scroll_x;
    out->scroll_y = input.scroll_y;
    out->left = input.left;
    out->right = input.right;
    out->middle = input.middle;
    out->space = input.space;
    out->r = input.r;
    context->input.scroll_x = 0;
    context->input.scroll_y = 0;
    return 0;
  );
}

int ss_gl_cursor_snapshot(void* handle, SSGLCursor* out) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(!out) throw std::invalid_argument("cursor snapshot output is null");
    out->x = context->input.x;
    out->y = context->input.y;
    return 0;
  );
}

int ss_gl_request_close(void* handle) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    glfwSetWindowShouldClose(context->window, GLFW_TRUE);
    return 0;
  );
}

int ss_gl_test_set_input(void* handle, const SSGLInput* input) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(!input) throw std::invalid_argument("test input is null");
    context->input.x = input->x;
    context->input.y = input->y;
    context->input.scroll_x = input->scroll_x;
    context->input.scroll_y = input->scroll_y;
    context->input.left = input->left;
    context->input.right = input->right;
    context->input.middle = input->middle;
    context->input.space = input->space;
    context->input.r = input->r;
    return 0;
  );
}

void* ss_gl_program(void* handle, const char* shader_key) noexcept {
  ss_try(nullptr,
    auto* context = require_context(handle);
    if(!shader_key || std::string(shader_key) != "paint")
      throw std::invalid_argument("unknown GL shader key; expected paint");
    return &context->paint_program;
  );
}

void* ss_gl_effect_program(
  void* handle, const char* program_key, const char* effect_key,
  const char* declarations, const char* vertex_hook, const char* fragment_hook,
  uint8_t requires_sdf, const SSGLUniform* uniforms, uint64_t uniform_count) noexcept {
  ss_try(nullptr,
    auto* context = require_context(handle);
    if(!program_key || !effect_key || !declarations || !vertex_hook || !fragment_hook)
      throw std::invalid_argument("effect source and cache keys must be non-null");
    if(uniform_count > 0 && !uniforms) throw std::invalid_argument("effect uniforms are null");
    if(auto found = context->effects.find(effect_key); found != context->effects.end())
      return found->second.get();
    glfwMakeContextCurrent(context->window);
    auto program = context->effect_programs.find(program_key);
    if(program == context->effect_programs.end()) {
      auto candidate = std::make_unique<GLProgram>();
      try {
        const std::string vertex_source = slipstream::reactive::effect_vertex_source(
          declarations, vertex_hook);
        const std::string fragment_source = slipstream::reactive::effect_fragment_source(
          declarations, fragment_hook, requires_sdf != 0);
        candidate->vertex = slipstream::reactive::compile_shader(
          GL_VERTEX_SHADER, vertex_source.c_str());
        ++context->shader_compilations;
        candidate->fragment = slipstream::reactive::compile_shader(
          GL_FRAGMENT_SHADER, fragment_source.c_str());
        ++context->shader_compilations;
        candidate->id = slipstream::reactive::link_program(candidate->vertex, candidate->fragment);
      } catch(...) {
        destroy_program(*candidate);
        throw;
      }
      program = context->effect_programs.emplace(program_key, std::move(candidate)).first;
      glUseProgram(program->second->id);
      glUniform1i(glGetUniformLocation(program->second->id, "u_curveTexture"), 0);
      glUniform1i(glGetUniformLocation(program->second->id, "u_bandTexture"), 1);
      glUniform1i(glGetUniformLocation(program->second->id, "u_gradientTexture"), 2);
      glUniform1i(glGetUniformLocation(program->second->id, "u_sdfTexture"), 3);
    }
    auto effect = std::make_unique<GLEffect>();
    effect->owner = context;
    effect->program = program->second.get();
    effect->requires_sdf = requires_sdf != 0;
    static constexpr uint32_t component_counts[] = {1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4,
                                                     1, 2, 3, 4, 9, 16};
    for(uint64_t i = 0; i < uniform_count; ++i) {
      const auto& source = uniforms[i];
      if(!source.name || source.type >= std::size(component_counts) ||
         source.components != component_counts[source.type])
        throw std::invalid_argument("invalid native effect uniform interface");
      NativeUniform target;
      target.name = source.name;
      target.type = source.type;
      target.components = source.components;
      std::copy(source.values, source.values + source.components, target.values.begin());
      std::copy(source.int_values, source.int_values + source.components, target.int_values.begin());
      std::copy(source.uint_values, source.uint_values + source.components, target.uint_values.begin());
      effect->uniforms.push_back(std::move(target));
    }
    auto* result = effect.get();
    context->effects.emplace(effect_key, std::move(effect));
    {
      std::lock_guard lock(registry_mutex);
      effects.insert(result);
    }
    return result;
  );
}

void* ss_gl_upload(void* handle, void* atlasHandle, const SSLayer* root) noexcept {
  ss_try(nullptr,
    auto* context = require_context(handle);
    auto* atlas = slipstream::require_atlas(atlasHandle);
    if(!root) throw std::invalid_argument("GL upload root layer is null");
    glfwMakeContextCurrent(context->window);
    auto paint_data = slipstream::reactive::build_paint_data(
      *atlas, root->gradient_id, root->pattern_id);
    std::unique_ptr<GLPaint> paint;
    if(paint_data.kind != 0) {
      paint.reset(new GLPaint);
      paint->owner = context;
      paint->data = paint_data;
    }
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
      if(!atlas->getSDF().texture.empty()) {
        const slughorn::Key key = root->key.type == 0
          ? slughorn::Key(root->key.codepoint)
          : slughorn::Key(root->key.name ? root->key.name : "");
        const auto shape = atlas->getShape(key);
        if(shape && shape->sdf) {
          const auto& tile = *shape->sdf;
          resource->sdf = slipstream::reactive::upload_texture(atlas->getSDF().texture);
          glBindTexture(GL_TEXTURE_2D, resource->sdf);
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
          glBindTexture(GL_TEXTURE_2D, 0);
          resource->sdf_type = atlas->getSDF().config.type == slughorn::Atlas::SDF::Type::SDF ? 0 : 1;
          resource->sdf_rect = {static_cast<float>(tile.x), static_cast<float>(tile.y),
                                static_cast<float>(tile.w), static_cast<float>(tile.h)};
          resource->sdf_frame = {tile.emOriginX, tile.emOriginY, tile.texelsPerEm, tile.range};
        }
      }
      if(paint && paint->data.kind == 1) {
        paint->texture = slipstream::reactive::upload_texture(atlas->getGradientTextureData());
        glBindTexture(GL_TEXTURE_2D, paint->texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);
      }
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
    resource->paint = paint.get();
    {
      std::lock_guard lock(registry_mutex);
      context->resources.insert(resource.get());
      resources.insert(resource.get());
      if(paint) paints.insert(paint.get());
    }
    ++context->resource_uploads;
    if(paint && paint->texture) ++context->paint_texture_uploads;
    paint.release();
    return resource.release();
  );
}

void* ss_gl_paint_texture(void* handle, void* resourceHandle) noexcept {
  ss_try(nullptr,
    auto* context = require_context(handle);
    return require_resource(context, resourceHandle)->paint;
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
      if(resource->paint) paints.erase(resource->paint);
    }
    delete resource;
    return 0;
  );
}

int ss_gl_render(void* handle, const SSGLDrawCommand* commands, uint64_t count) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    context->draw_commands = 0;
    context->geometry_commands = 0;
    context->group_commands = 0;
    gl_error_command = -1;
    validate_commands(context, commands, count);
    glfwMakeContextCurrent(context->window);
    int width = 0;
    int height = 0;
    glfwGetFramebufferSize(context->window, &width, &height);
    if(width == 0 || height == 0) return 0;
    float aspect = static_cast<float>(width) / height;
    float bounds[4] = {0, aspect, 0, 1};
    if(context->has_bounds) std::copy(context->bounds, context->bounds + 4, bounds);
    if(bounds[0] == bounds[1] || bounds[2] == bounds[3])
      throw std::invalid_argument("GL orthographic bounds must have nonzero area");
    float projection[16];
    slipstream::reactive::ortho_matrix(bounds[0], bounds[1], bounds[2], bounds[3],
                                       -100.0f, 100.0f, projection);
    context->compositor->begin_frame(width, height, bounds);
    try {
      for(uint64_t i = 0; i < count; ++i) {
        gl_error_command = static_cast<int64_t>(i);
        const auto& command = commands[i];
        ++context->draw_commands;
        if(command.kind == 0) ++context->geometry_commands;
        else ++context->group_commands;
        if(command.kind == 2) {
          context->compositor->end_group(command.blend_mode);
          continue;
        }
        if(command.kind == 1) {
          slipstream::reactive::CompositeMask mask;
          mask.type = command.mask_type == 255 ? -1 : command.mask_type;
          mask.invert = command.mask_invert != 0;
          std::copy(command.mask_params, command.mask_params + 6, mask.params.begin());
          mask.transform = {command.transform.x, command.transform.y, command.transform.z};
          mask.scale = command.scale;
          mask.opacity = command.color.a;
          if(mask.type == 0) {
            const auto* resource = require_resource(context, command.handle);
            mask.texture = resource->sdf;
            mask.sdf_type = resource->sdf_type;
            mask.sdf_rect = resource->sdf_rect;
            mask.sdf_frame = resource->sdf_frame;
          }
          context->compositor->begin_group(mask);
          continue;
        }
        if(!command.visible) continue;
        auto* resource = require_resource(context, command.handle);
        auto* effect = command.effect ? require_effect(context, command.effect) : nullptr;
        context->effect_param = command.effect_param;
        context->compositor->draw([context, resource, effect, &command, projection]() {
          const auto* paint = resource->paint;
          const GLuint program = effect ? effect->program->id : context->paint_program.id;
          glDisable(GL_BLEND);
          glUseProgram(program);
          glUniformMatrix4fv(glGetUniformLocation(program, "u_mvp"), 1, GL_FALSE, projection);
          glUniform3f(glGetUniformLocation(program, "u_transform"),
                      command.transform.x, command.transform.y, command.transform.z);
          glUniform1f(glGetUniformLocation(program, "u_scale"), command.scale);
          glUniform4f(glGetUniformLocation(program, "u_color"),
                      command.color.r, command.color.g, command.color.b, command.color.a);
          glUniform1i(glGetUniformLocation(program, "u_textureWidth"), resource->texture_width);
          glUniform1i(glGetUniformLocation(program, "u_paintKind"), paint ? paint->data.kind : 0);
          glUniform1i(glGetUniformLocation(program, "u_gradientCount"),
                      paint ? paint->data.gradient_count : 0);
          if(effect) {
            glUniform1i(glGetUniformLocation(program, "u_effectId"),
                        static_cast<int>(command.effect_id));
            glUniform1f(glGetUniformLocation(program, "u_effectParam"), command.effect_param);
            glUniform1f(glGetUniformLocation(program, "u_bleed"), command.bleed);
            glUniform1i(glGetUniformLocation(program, "u_sdfType"), resource->sdf_type);
            glUniform4fv(glGetUniformLocation(program, "u_sdfRect"), 1, resource->sdf_rect.data());
            glUniform4fv(glGetUniformLocation(program, "u_sdfFrame"), 1, resource->sdf_frame.data());
            apply_effect_uniforms(program, effect);
          }
          if(paint) {
            glUniform4fv(glGetUniformLocation(program, "u_paintMeta"), 1, paint->data.meta.data());
            glUniform4fv(glGetUniformLocation(program, "u_paintTransform"), 1,
                         paint->data.transform.data());
            glUniform4fv(glGetUniformLocation(program, "u_patternCell"), 1, paint->data.cell.data());
            glUniform4fv(glGetUniformLocation(program, "u_patternBandTransform"), 1,
                         paint->data.band_transform.data());
            glUniform4fv(glGetUniformLocation(program, "u_patternShapeData"), 1,
                         paint->data.shape_data.data());
          }
          glActiveTexture(GL_TEXTURE0);
          glBindTexture(GL_TEXTURE_2D, resource->curve);
          glActiveTexture(GL_TEXTURE1);
          glBindTexture(GL_TEXTURE_2D, resource->band);
          glActiveTexture(GL_TEXTURE2);
          glBindTexture(GL_TEXTURE_2D,
                        paint && paint->texture ? paint->texture : context->dummy_gradient);
          if(effect) {
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, resource->sdf ? resource->sdf : context->dummy_gradient);
          }
          glBindVertexArray(resource->vao);
          glDrawElements(GL_TRIANGLES, resource->index_count, GL_UNSIGNED_SHORT, nullptr);
        }, command.draw_mode, command.blend_mode);
      }
      context->compositor->end_frame();
    } catch(...) {
      context->compositor->restore();
      throw;
    }
    gl_error_command = -1;
    return 0;
  );
}

int64_t ss_gl_error_command(void* handle) noexcept {
  if(!handle) return -1;
  auto* context = static_cast<GLContext*>(handle);
  std::lock_guard lock(registry_mutex);
  if(!contexts.contains(context)) return -1;
  return gl_error_command;
}

int ss_gl_swap(void* handle) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    glfwSwapBuffers(context->window);
    return 0;
  );
}

int ss_gl_test_resize(void* handle, int32_t width, int32_t height) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(width < 0 || height < 0) throw std::invalid_argument("window size must not be negative");
    glfwSetWindowSize(context->window, width, height);
    glfwPollEvents();
    return 0;
  );
}

int ss_gl_test_bind_nondefault(
  void* handle, int32_t x, int32_t y, int32_t width, int32_t height) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(width <= 0 || height <= 0) throw std::invalid_argument("test viewport must be positive");
    glfwMakeContextCurrent(context->window);
    if(!context->test_fbo) {
      glGenTextures(1, &context->test_texture);
      glBindTexture(GL_TEXTURE_2D, context->test_texture);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
      glGenFramebuffers(1, &context->test_fbo);
      glBindFramebuffer(GL_FRAMEBUFFER, context->test_fbo);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                             context->test_texture, 0);
      if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        throw std::runtime_error("test framebuffer is incomplete");
    }
    glBindFramebuffer(GL_FRAMEBUFFER, context->test_fbo);
    glViewport(x, y, width, height);
    return 0;
  );
}

int ss_gl_test_restore_default(void* handle) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    glfwMakeContextCurrent(context->window);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    int width = 0;
    int height = 0;
    glfwGetFramebufferSize(context->window, &width, &height);
    glViewport(0, 0, width, height);
    return 0;
  );
}

int ss_gl_test_fail_next(void* handle, uint32_t point) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    context->compositor->fail_next_for_test(point);
    return 0;
  );
}

int ss_gl_test_frame_state(void* handle, SSGLFrameState* out) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(!out) throw std::invalid_argument("frame state output is null");
    glfwMakeContextCurrent(context->window);
    std::memset(out, 0, sizeof(*out));
    glGetIntegerv(GL_VIEWPORT, out->viewport);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &out->framebuffer_binding);
    glfwGetFramebufferSize(context->window, &out->framebuffer_size[0], &out->framebuffer_size[1]);
    glfwGetWindowSize(context->window, &out->window_size[0], &out->window_size[1]);
    glGetUniformfv(context->paint_program.id,
                   glGetUniformLocation(context->paint_program.id, "u_mvp"), out->projection);
    out->shader_compilations = context->shader_compilations;
    out->resource_uploads = context->resource_uploads;
    out->paint_texture_uploads = context->paint_texture_uploads;
    out->effect_param = context->effect_param;
    out->live_resources = static_cast<uint32_t>(context->resources.size());
    out->live_effects = static_cast<uint32_t>(context->effects.size());
    out->draw_commands = context->draw_commands;
    out->geometry_commands = context->geometry_commands;
    out->group_commands = context->group_commands;
    return 0;
  );
}

int ss_gl_test_capture(void* handle, uint8_t* out, uint64_t capacity) noexcept {
  ss_try(1,
    auto* context = require_context(handle);
    if(!out) throw std::invalid_argument("capture output is null");
    glfwMakeContextCurrent(context->window);
    int width = 0;
    int height = 0;
    glfwGetFramebufferSize(context->window, &width, &height);
    if(width <= 0 || height <= 0) throw std::runtime_error("capture framebuffer is empty");
    const uint64_t required = static_cast<uint64_t>(width) * height * 4;
    if(capacity < required) throw std::invalid_argument("capture output buffer is too small");
    glReadBuffer(GL_BACK);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, out);
    const GLenum error = glGetError();
    if(error != GL_NO_ERROR)
      throw std::runtime_error("glReadPixels failed with GL error " + std::to_string(error));
    return 0;
  );
}
