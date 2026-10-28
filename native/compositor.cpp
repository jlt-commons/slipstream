#include "compositor.hpp"

#include "reactive_renderer.hpp"

#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

const char* fullscreen_vertex_source() {
  return R"(
#version 330 core
void main() {
  vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
  gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
)";
}

const char* copy_fragment_source() {
  return R"(
#version 330 core
uniform sampler2D u_image;
out vec4 fragColor;
void main() {
  fragColor = texelFetch(u_image, ivec2(gl_FragCoord.xy), 0);
}
)";
}

const char* composite_fragment_source() {
  return R"(
#version 330 core
uniform sampler2D u_source;
uniform sampler2D u_destination;
uniform sampler2D u_sdfTexture;
uniform int u_blendMode;
uniform float u_opacity;
uniform int u_maskType;
uniform bool u_maskInvert;
uniform float u_maskParams[6];
uniform vec3 u_maskTransform;
uniform float u_maskScale;
uniform vec4 u_bounds;
uniform vec2 u_framebufferSize;
uniform int u_sdfType;
uniform vec4 u_sdfRect;
uniform vec4 u_sdfFrame;
out vec4 fragColor;

float maskCoverage(float distanceValue, vec2 emsPerPixel) {
  return clamp(0.5 - distanceValue / max(max(emsPerPixel.x, emsPerPixel.y), 1e-8), 0.0, 1.0);
}
float sdfCircle(vec2 p, vec2 center, float radius) {
  return length(p - center) - radius;
}
float sdfRect(vec2 p, vec2 center, vec2 halfExtents) {
  vec2 d = abs(p - center) - halfExtents;
  return length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0);
}
float sdfCapsule(vec2 p, vec2 a, vec2 b, float radius) {
  vec2 pa = p - a;
  vec2 ba = b - a;
  float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-12), 0.0, 1.0);
  return length(pa - ba * h) - radius;
}
float sdfArc(vec2 p, vec2 center, float radius, float angleStart, float angleEnd) {
  vec2 q = p - center;
  float midAngle = (angleStart + angleEnd) * 0.5;
  float halfSpan = (angleEnd - angleStart) * 0.5;
  vec2 sc = vec2(sin(halfSpan), cos(halfSpan));
  float cosM = cos(-midAngle);
  float sinM = sin(-midAngle);
  vec2 rp = vec2(q.x * cosM - q.y * sinM, q.x * sinM + q.y * cosM);
  rp.x = abs(rp.x);
  float l = length(rp) - radius;
  float m = length(rp - sc * clamp(dot(rp, sc), 0.0, radius));
  return max(l, m * sign(sc.y * rp.x - sc.x * rp.y));
}
float sdfArcBand(vec2 p, vec2 center, float radius, float angleStart,
                 float angleEnd, float strokeHalfWidth) {
  vec2 q = p - center;
  float midAngle = (angleStart + angleEnd) * 0.5;
  float halfSpan = (angleEnd - angleStart) * 0.5;
  float cosM = cos(-midAngle);
  float sinM = sin(-midAngle);
  vec2 rp = vec2(q.x * cosM - q.y * sinM, q.x * sinM + q.y * cosM);
  rp.y = abs(rp.y);
  vec2 sc = vec2(cos(halfSpan), sin(halfSpan));
  float k = sc.x * rp.y > sc.y * rp.x ? dot(rp, sc) : length(rp);
  return sqrt(max(dot(rp, rp) + radius * radius - 2.0 * radius * k, 0.0)) - strokeHalfWidth;
}
vec2 sdfRotate(vec2 p, float angle) {
  float c = cos(angle);
  float s = sin(angle);
  return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
}
float sdfHexagon(vec2 p, vec2 center, float radius, float rotation) {
  vec2 q = abs(sdfRotate(p - center, -rotation));
  const vec3 k = vec3(-0.866025404, 0.5, 0.577350269);
  q -= 2.0 * min(dot(k.xy, q), 0.0) * k.xy;
  q -= vec2(clamp(q.x, -k.z * radius, k.z * radius), radius);
  return length(q) * sign(q.y);
}
float sdfOctagon(vec2 p, vec2 center, float radius, float rotation) {
  vec2 q = abs(sdfRotate(p - center, -rotation));
  const vec3 k = vec3(-0.9238795325, 0.3826834323, 0.4142135623);
  q -= 2.0 * min(dot(vec2(k.x, k.y), q), 0.0) * vec2(k.x, k.y);
  q -= 2.0 * min(dot(vec2(-k.x, k.y), q), 0.0) * vec2(-k.x, k.y);
  q -= vec2(clamp(q.x, -k.z * radius, k.z * radius), radius);
  return length(q) * sign(q.y);
}
float sdfStar(vec2 p, vec2 center, float radius, float points,
              float innerRatio, float rotation) {
  vec2 q = sdfRotate(p - center, -rotation);
  float n = max(round(points), 3.0);
  float m = mix(2.0, n, clamp(innerRatio, 0.0, 1.0));
  float an = 3.14159265 / n;
  float en = 3.14159265 / m;
  vec2 acs = vec2(cos(an), sin(an));
  vec2 ecs = vec2(cos(en), sin(en));
  float bn = mod(atan(q.x, q.y), 2.0 * an) - an;
  q = length(q) * vec2(cos(bn), abs(sin(bn)));
  q -= radius * acs;
  q += ecs * clamp(-dot(q, ecs), 0.0, radius * acs.y / ecs.y);
  return length(q) * sign(q.x);
}
float median3(vec3 value) {
  return max(min(value.r, value.g), min(max(value.r, value.g), value.b));
}
vec2 tileEm(vec2 canvasCoord) {
  float scale = max(u_maskParams[2], 1e-6);
  vec2 center = u_sdfFrame.xy + u_sdfRect.zw * (0.5 / u_sdfFrame.z);
  vec2 em = canvasCoord - vec2(u_maskParams[0], u_maskParams[1]);
  return center + (em - center) / scale;
}
bool insideTile(vec2 em) {
  vec2 t = (em - u_sdfFrame.xy) * u_sdfFrame.z;
  return all(greaterThanEqual(t, vec2(0.0))) && all(lessThanEqual(t, u_sdfRect.zw));
}
float tileSample(vec2 em) {
  vec2 texel = clamp(u_sdfRect.xy + (em - u_sdfFrame.xy) * u_sdfFrame.z,
                     u_sdfRect.xy + 0.5, u_sdfRect.xy + u_sdfRect.zw - 0.5);
  vec3 value = texture(u_sdfTexture, texel / vec2(textureSize(u_sdfTexture, 0))).rgb;
  return u_sdfType == 0 ? value.r : median3(value);
}
float evaluateMask(vec2 world) {
  if(u_maskType < 0) return 1.0;
  float scale = max(abs(u_maskScale), 1e-6);
  vec2 p = (world - u_maskTransform.xy) / scale;
  vec2 worldPerPixel = (u_bounds.zw - u_bounds.xy) / u_framebufferSize;
  vec2 emsPerPixel = abs(worldPerPixel) / scale;
  float coverage;
  if(u_maskType == 0) {
    vec2 em = tileEm(p);
    if(u_sdfRect.z <= 0.0 || !insideTile(em)) {
      coverage = 0.0;
    } else {
      float distanceValue = tileSample(em);
      float screenPixelRange = max(2.0 * u_sdfFrame.w * max(u_maskParams[2], 1e-6) /
                                   max(max(emsPerPixel.x, emsPerPixel.y), 1e-8), 1.0);
      coverage = clamp((distanceValue - 0.5) * screenPixelRange + 0.5, 0.0, 1.0);
    }
  } else if(u_maskType == 1) {
    coverage = maskCoverage(sdfCircle(p, vec2(u_maskParams[0], u_maskParams[1]), u_maskParams[2]), emsPerPixel);
  } else if(u_maskType == 2) {
    vec2 center = vec2(u_maskParams[0], u_maskParams[1]) + vec2(u_maskParams[2], u_maskParams[3]) * 0.5;
    coverage = maskCoverage(sdfRect(p, center, vec2(u_maskParams[2], u_maskParams[3]) * 0.5), emsPerPixel);
  } else if(u_maskType == 3) {
    coverage = maskCoverage(sdfCapsule(p, vec2(u_maskParams[0], u_maskParams[1]), vec2(u_maskParams[2], u_maskParams[3]),
                                      u_maskParams[4]), emsPerPixel);
  } else if(u_maskType == 4) {
    coverage = maskCoverage(sdfArc(p, vec2(u_maskParams[0], u_maskParams[1]), u_maskParams[2],
                                  u_maskParams[3], u_maskParams[4]), emsPerPixel);
  } else if(u_maskType == 5) {
    coverage = maskCoverage(sdfArcBand(p, vec2(u_maskParams[0], u_maskParams[1]), u_maskParams[2],
                                      u_maskParams[3], u_maskParams[4], u_maskParams[5]),
                            emsPerPixel);
  } else if(u_maskType == 6) {
    coverage = maskCoverage(sdfHexagon(p, vec2(u_maskParams[0], u_maskParams[1]), u_maskParams[2],
                                      u_maskParams[3]), emsPerPixel);
  } else if(u_maskType == 7) {
    coverage = maskCoverage(sdfOctagon(p, vec2(u_maskParams[0], u_maskParams[1]), u_maskParams[2],
                                      u_maskParams[3]), emsPerPixel);
  } else {
    coverage = maskCoverage(sdfStar(p, vec2(u_maskParams[0], u_maskParams[1]), u_maskParams[2],
                                   u_maskParams[3], u_maskParams[4], u_maskParams[5]),
                            emsPerPixel);
  }
  return u_maskInvert ? 1.0 - coverage : coverage;
}

vec2 porterDuffFactors(float sa, float da) {
  if(u_blendMode == 0) return vec2(1.0, 1.0 - sa);
  if(u_blendMode == 1) return vec2(1.0, 0.0);
  if(u_blendMode == 2) return vec2(0.0, 1.0);
  if(u_blendMode == 3) return vec2(da, 0.0);
  if(u_blendMode == 4) return vec2(0.0, sa);
  if(u_blendMode == 5) return vec2(1.0 - da, 0.0);
  if(u_blendMode == 6) return vec2(0.0, 1.0 - sa);
  if(u_blendMode == 7) return vec2(da, 1.0 - sa);
  if(u_blendMode == 8) return vec2(1.0 - da, sa);
  if(u_blendMode == 9) return vec2(1.0 - da, 1.0 - sa);
  if(u_blendMode == 10) return vec2(0.0);
  return vec2(1.0 - da, 1.0);
}
float softLightD(float cb) {
  return cb <= 0.25 ? ((16.0 * cb - 12.0) * cb + 4.0) * cb : sqrt(cb);
}
float photoshopChannel(float cb, float cs) {
  if(u_blendMode == 20) return cb * cs;
  if(u_blendMode == 21) return cb + cs - cb * cs;
  if(u_blendMode == 22) return cb <= 0.5 ? 2.0 * cb * cs : 1.0 - 2.0 * (1.0 - cb) * (1.0 - cs);
  if(u_blendMode == 23) return min(cb, cs);
  if(u_blendMode == 24) return max(cb, cs);
  if(u_blendMode == 25) return cs >= 1.0 ? 1.0 : min(1.0, cb / max(1.0 - cs, 1e-8));
  if(u_blendMode == 26) return cs <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - cb) / cs);
  if(u_blendMode == 27) return cs <= 0.5 ? 2.0 * cb * cs : 1.0 - 2.0 * (1.0 - cb) * (1.0 - cs);
  if(u_blendMode == 28) {
    return cs <= 0.5 ? cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb)
                     : cb + (2.0 * cs - 1.0) * (softLightD(cb) - cb);
  }
  if(u_blendMode == 29) return abs(cb - cs);
  return cb + cs - 2.0 * cb * cs;
}
void main() {
  vec4 source = texelFetch(u_source, ivec2(gl_FragCoord.xy), 0);
  vec4 destination = texelFetch(u_destination, ivec2(gl_FragCoord.xy), 0);
  vec2 world = u_bounds.xy + gl_FragCoord.xy / u_framebufferSize * (u_bounds.zw - u_bounds.xy);
  source *= u_opacity * evaluateMask(world);
  float sa = source.a;
  float da = destination.a;
  if(u_blendMode < 20) {
    vec2 factors = porterDuffFactors(sa, da);
    fragColor = factors.x * source + factors.y * destination;
  } else {
    vec3 cs = sa > 0.0 ? source.rgb / sa : vec3(0.0);
    vec3 cb = da > 0.0 ? destination.rgb / da : vec3(0.0);
    vec3 blended = vec3(photoshopChannel(cb.r, cs.r),
                        photoshopChannel(cb.g, cs.g),
                        photoshopChannel(cb.b, cs.b));
    fragColor.rgb = (1.0 - da) * source.rgb + (1.0 - sa) * destination.rgb + sa * da * blended;
    fragColor.a = sa + da - sa * da;
  }
}
)";
}

struct Target {
  GLuint texture = 0;
  GLuint fbo = 0;
  int width = 0;
  int height = 0;

  ~Target() {
    if(fbo) glDeleteFramebuffers(1, &fbo);
    if(texture) glDeleteTextures(1, &texture);
  }

  Target() = default;
  Target(const Target&) = delete;
  Target& operator=(const Target&) = delete;
  Target(Target&& other) noexcept {
    std::swap(texture, other.texture);
    std::swap(fbo, other.fbo);
    std::swap(width, other.width);
    std::swap(height, other.height);
  }
  Target& operator=(Target&& other) noexcept {
    std::swap(texture, other.texture);
    std::swap(fbo, other.fbo);
    std::swap(width, other.width);
    std::swap(height, other.height);
    return *this;
  }

  void resize(int new_width, int new_height) {
    if(width == new_width && height == new_height) return;
    if(!texture) glGenTextures(1, &texture);
    if(!fbo) glGenFramebuffers(1, &fbo);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, new_width, new_height, 0,
                 GL_RGBA, GL_FLOAT, nullptr);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
      throw std::runtime_error("compositor framebuffer is incomplete");
    width = new_width;
    height = new_height;
  }
};

struct Level {
  Target targets[2];
  int active = 0;
};

void validate_blend(uint8_t mode) {
  if((mode > 11 && mode < 20) || mode > 30)
    throw std::invalid_argument("unknown blend mode " + std::to_string(mode));
}

}

namespace slipstream::reactive {

struct Compositor::Impl {
  GLuint composite_program = 0;
  GLuint composite_vertex = 0;
  GLuint composite_fragment = 0;
  GLuint copy_program = 0;
  GLuint copy_vertex = 0;
  GLuint copy_fragment = 0;
  GLuint vao = 0;
  Target scratch;
  std::vector<Level> levels{1};
  std::vector<CompositeMask> groups;
  int width = 0;
  int height = 0;
  float bounds[4] = {0, 1, 0, 1};
  GLint previous_fbo = 0;
  GLint previous_viewport[4] = {0, 0, 0, 0};
  bool frame_active = false;

  Impl() {
    composite_vertex = compile_shader(GL_VERTEX_SHADER, fullscreen_vertex_source());
    composite_fragment = compile_shader(GL_FRAGMENT_SHADER, composite_fragment_source());
    composite_program = link_program(composite_vertex, composite_fragment);
    copy_vertex = compile_shader(GL_VERTEX_SHADER, fullscreen_vertex_source());
    copy_fragment = compile_shader(GL_FRAGMENT_SHADER, copy_fragment_source());
    copy_program = link_program(copy_vertex, copy_fragment);
    glGenVertexArrays(1, &vao);
    glUseProgram(composite_program);
    glUniform1i(glGetUniformLocation(composite_program, "u_source"), 0);
    glUniform1i(glGetUniformLocation(composite_program, "u_destination"), 1);
    glUniform1i(glGetUniformLocation(composite_program, "u_sdfTexture"), 2);
    glUseProgram(copy_program);
    glUniform1i(glGetUniformLocation(copy_program, "u_image"), 0);
    glUseProgram(0);
  }

  ~Impl() {
    if(vao) glDeleteVertexArrays(1, &vao);
    if(composite_program) glDeleteProgram(composite_program);
    if(composite_vertex) glDeleteShader(composite_vertex);
    if(composite_fragment) glDeleteShader(composite_fragment);
    if(copy_program) glDeleteProgram(copy_program);
    if(copy_vertex) glDeleteShader(copy_vertex);
    if(copy_fragment) glDeleteShader(copy_fragment);
  }

  void bind_target(Target& target) {
    glBindFramebuffer(GL_FRAMEBUFFER, target.fbo);
    glViewport(0, 0, width, height);
  }

  void clear(Target& target, float r, float g, float b, float a) {
    bind_target(target);
    glClearColor(r, g, b, a);
    glClear(GL_COLOR_BUFFER_BIT);
  }

  void resize(int new_width, int new_height) {
    if(width == new_width && height == new_height) return;
    width = new_width;
    height = new_height;
    scratch.resize(width, height);
    for(auto& level : levels) {
      level.targets[0].resize(width, height);
      level.targets[1].resize(width, height);
    }
  }

  void composite(GLuint source, uint8_t blend_mode, const CompositeMask* mask) {
    validate_blend(blend_mode);
    auto& level = levels.back();
    Target& destination = level.targets[level.active];
    Target& output = level.targets[1 - level.active];
    bind_target(output);
    glDisable(GL_BLEND);
    glUseProgram(composite_program);
    glUniform1i(glGetUniformLocation(composite_program, "u_blendMode"), blend_mode);
    glUniform1f(glGetUniformLocation(composite_program, "u_opacity"), mask ? mask->opacity : 1.0f);
    glUniform1i(glGetUniformLocation(composite_program, "u_maskType"), mask ? mask->type : -1);
    glUniform1i(glGetUniformLocation(composite_program, "u_maskInvert"), mask && mask->invert);
    const std::array<float, 6> empty_params{};
    const auto& params = mask ? mask->params : empty_params;
    glUniform1fv(glGetUniformLocation(composite_program, "u_maskParams"), 6, params.data());
    const std::array<float, 3> empty_transform{};
    const auto& transform = mask ? mask->transform : empty_transform;
    glUniform3fv(glGetUniformLocation(composite_program, "u_maskTransform"), 1, transform.data());
    glUniform1f(glGetUniformLocation(composite_program, "u_maskScale"), mask ? mask->scale : 1.0f);
    glUniform4f(glGetUniformLocation(composite_program, "u_bounds"),
                bounds[0], bounds[2], bounds[1], bounds[3]);
    glUniform2f(glGetUniformLocation(composite_program, "u_framebufferSize"), width, height);
    glUniform1i(glGetUniformLocation(composite_program, "u_sdfType"), mask ? mask->sdf_type : 0);
    const std::array<float, 4> empty_rect{};
    const auto& rect = mask ? mask->sdf_rect : empty_rect;
    const auto& frame = mask ? mask->sdf_frame : empty_rect;
    glUniform4fv(glGetUniformLocation(composite_program, "u_sdfRect"), 1, rect.data());
    glUniform4fv(glGetUniformLocation(composite_program, "u_sdfFrame"), 1, frame.data());
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, source);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, destination.texture);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, mask && mask->texture ? mask->texture : destination.texture);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    level.active = 1 - level.active;
  }

  void restore() {
    if(!frame_active) return;
    glBindFramebuffer(GL_FRAMEBUFFER, previous_fbo);
    glViewport(previous_viewport[0], previous_viewport[1],
               previous_viewport[2], previous_viewport[3]);
    glBindVertexArray(0);
    glUseProgram(0);
    glDisable(GL_BLEND);
    frame_active = false;
  }
};

Compositor::Compositor() : impl(new Impl) {}
Compositor::~Compositor() = default;

void Compositor::begin_frame(int new_width, int new_height, const float new_bounds[4]) {
  if(new_width <= 0 || new_height <= 0)
    throw std::invalid_argument("compositor framebuffer is empty");
  if(impl->frame_active) throw std::logic_error("compositor frame is already active");
  glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &impl->previous_fbo);
  glGetIntegerv(GL_VIEWPORT, impl->previous_viewport);
  impl->frame_active = true;
  try {
    impl->resize(new_width, new_height);
    std::copy(new_bounds, new_bounds + 4, impl->bounds);
    impl->levels.resize(1);
    impl->levels[0].active = 0;
    impl->groups.clear();
    glDisable(GL_BLEND);
    impl->clear(impl->levels[0].targets[0], 0.15f, 0.15f, 0.15f, 1.0f);
  } catch(...) {
    impl->restore();
    throw;
  }
}

void Compositor::draw(const std::function<void()>& draw_geometry,
                      uint8_t draw_mode, uint8_t blend_mode) {
  if(!impl->frame_active) throw std::logic_error("compositor frame is not active");
  if(draw_mode > 3) throw std::invalid_argument("unknown draw mode " + std::to_string(draw_mode));
  validate_blend(blend_mode);
  if(draw_mode != 0) return;
  impl->clear(impl->scratch, 0, 0, 0, 0);
  draw_geometry();
  impl->composite(impl->scratch.texture, blend_mode, nullptr);
}

void Compositor::begin_group(const CompositeMask& mask) {
  if(!impl->frame_active) throw std::logic_error("compositor frame is not active");
  if(mask.type < -1 || mask.type > 8) throw std::invalid_argument("unknown mask type");
  impl->levels.emplace_back();
  auto& level = impl->levels.back();
  level.targets[0].resize(impl->width, impl->height);
  level.targets[1].resize(impl->width, impl->height);
  level.active = 0;
  impl->groups.push_back(mask);
  impl->clear(level.targets[0], 0, 0, 0, 0);
}

void Compositor::end_group(uint8_t blend_mode) {
  if(!impl->frame_active) throw std::logic_error("compositor frame is not active");
  if(impl->levels.size() < 2) throw std::invalid_argument("end-group has no matching begin-group");
  validate_blend(blend_mode);
  Level child = std::move(impl->levels.back());
  const CompositeMask mask = impl->groups.back();
  impl->levels.pop_back();
  impl->groups.pop_back();
  impl->composite(child.targets[child.active].texture, blend_mode, &mask);
}

void Compositor::end_frame() {
  if(!impl->frame_active) throw std::logic_error("compositor frame is not active");
  if(impl->levels.size() != 1)
    throw std::invalid_argument("begin-group has no matching end-group");
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glViewport(0, 0, impl->width, impl->height);
  glDisable(GL_BLEND);
  glUseProgram(impl->copy_program);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, impl->levels[0].targets[impl->levels[0].active].texture);
  glBindVertexArray(impl->vao);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  impl->restore();
}

void Compositor::restore() {
  impl->restore();
}

}
