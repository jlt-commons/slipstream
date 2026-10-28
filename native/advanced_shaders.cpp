#include "reactive_renderer.hpp"

namespace slipstream::reactive {

const char* paint_vertex_source() {
  return R"(
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
}

const char* paint_fragment_source() {
  return R"(
#version 330 core
in vec2 v_emCoord;
in vec4 v_color;
flat in vec4 v_bandTransform;
flat in vec4 v_shapeData;
uniform sampler2D u_curveTexture;
uniform usampler2D u_bandTexture;
uniform sampler2D u_gradientTexture;
uniform int u_textureWidth;
uniform int u_paintKind;
uniform int u_gradientCount;
uniform vec4 u_paintMeta;
uniform vec4 u_paintTransform;
uniform vec4 u_patternCell;
uniform vec4 u_patternBandTransform;
uniform vec4 u_patternShapeData;
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
float slug_Render(vec2 renderCoord, vec2 pixelsPerEm, vec4 bandTransform,
                  ivec2 glyphLoc, ivec2 bandMax) {
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
float pattern_Fill(vec2 em, vec2 emsPerPixel) {
  vec2 col0 = u_paintMeta.yz;
  vec2 col1 = vec2(u_paintMeta.w, u_paintTransform.x);
  vec2 p = col0 * em.x + col1 * em.y + u_paintTransform.yz;
  vec2 size = u_patternCell.zw;
  vec2 q = p - u_patternCell.xy;
  vec2 k = floor(q / size);
  q -= k * size;
  int reflectBits = int(u_paintTransform.w + 0.5);
  if((reflectBits & 1) != 0 && mod(k.x, 2.0) != 0.0) q.x = size.x - q.x;
  if((reflectBits & 2) != 0 && mod(k.y, 2.0) != 0.0) q.y = size.y - q.y;
  vec2 tileEmsPerPixel = abs(col0) * emsPerPixel.x + abs(col1) * emsPerPixel.y;
  return slug_Render(u_patternCell.xy + q, 1.0 / tileEmsPerPixel,
                     u_patternBandTransform, ivec2(u_patternShapeData.xy),
                     ivec2(u_patternShapeData.zw));
}
vec4 gradient_Color(vec2 em) {
  float t;
  if(u_paintTransform.w == 0.0) {
    t = dot(em, u_paintTransform.xy) + u_paintTransform.z;
  } else if(u_paintTransform.w > 0.0) {
    vec2 d = em - u_paintMeta.yz;
    t = length(mat2(u_paintTransform) * d) - u_paintMeta.w;
  } else {
    float angle = atan(em.y - u_paintTransform.y, em.x - u_paintTransform.x);
    t = (angle - u_paintTransform.z) * (-u_paintTransform.w);
  }
  t = clamp(t, 0.0, 1.0);
  float row = (u_paintMeta.x - 0.5) / float(u_gradientCount);
  return texture(u_gradientTexture, vec2(t, row));
}
void main() {
  vec2 emsPerPixel = fwidth(v_emCoord);
  float fill = slug_Render(v_emCoord, 1.0 / emsPerPixel, v_bandTransform,
                           ivec2(v_shapeData.xy), ivec2(v_shapeData.zw));
  if(u_paintKind == 2 && fill > 0.0) fill *= pattern_Fill(v_emCoord, emsPerPixel);
  if(fill < 0.001) discard;
  vec4 color = v_color;
  if(u_paintKind == 1) {
    vec4 gradient = gradient_Color(v_emCoord);
    color = vec4(gradient.rgb, gradient.a * v_color.a);
  }
  fragColor = vec4(color.rgb, fill * color.a);
}
)";
}

}
