// Reference GLFW + GLAD backend for a borrowed, built Slughorn atlas.
// Jolt owns scene construction; this file owns the OpenGL loop and resources.

#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

#include <glad/glad.h>
#include <GLFW/glfw3.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <iterator>
#include <vector>
#include <string>

#include <unistd.h>

// ============================================================================
// Inline shaders
// ============================================================================

static const char* k_VertSrc = R"(
#version 330 core

layout(location = 0) in vec3 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec4 a_emCoord;
layout(location = 3) in vec4 a_bandTransform; // bandScaleX/Y, bandOffsetX/Y
layout(location = 4) in vec4 a_shapeData; // bandTexX/Y, bandMaxX/Y
layout(location = 5) in float a_effectId; // unused in this demo, carried through

uniform mat4 u_mvp;
uniform float u_time;

out vec2 v_emCoord;
out vec2 v_uv;
out vec4 v_color;

flat out vec4 v_bandTransform;
flat out vec4 v_shapeData;

void main() {
	v_emCoord = a_emCoord.xy;
	v_uv = a_emCoord.zw;
	v_color = a_color;
	v_bandTransform = a_bandTransform;
	v_shapeData = a_shapeData;

	gl_Position = u_mvp * vec4(a_position, 1.0);
}
)";

// Fragment shader: Slug core only. No debug modes, no effect branches -- just
// the coverage solve and a single standard fill. Add effects / debug modes
// from osgSlug's fragment shader when needed.
static const char* k_FragSrc = R"(
#version 330 core

in vec2 v_emCoord;
in vec2 v_uv;
in vec4 v_color;

flat in vec4 v_bandTransform; // bandScaleX/Y, bandOffsetX/Y
flat in vec4 v_shapeData; // bandTexX/Y, bandMaxX/Y

uniform sampler2D u_curveTexture;
uniform usampler2D u_bandTexture;

out vec4 fragColor;

// Hardcoded to log2(512) = 9, matching the default Atlas() constructor.
// If using Atlas(uint32_t texWidth), replace with: int(log2(atlas.getTextureWidth()))
// and pass as a uniform rather than a define.
#define TEX_WIDTH 9

// Must match slughorn::Atlas::INDIRECTION_SIZE
#define SLUG_INDIRECTION_SIZE 32

// ---------------------------------------------------------------------------
// Slug core (Lengyel 2017); identical to osgSlug's implementation.
// ---------------------------------------------------------------------------

uint slug_CalcRootCode(float y1, float y2, float y3) {
	uint i1 = floatBitsToUint(y1) >> 31u;
	uint i2 = floatBitsToUint(y2) >> 30u;
	uint i3 = floatBitsToUint(y3) >> 29u;

	uint shift = (i2 & 2u) | (i1 & ~2u);
	shift = (i3 & 4u) | (shift & ~4u);

	return ((0x2E74u >> shift) & 0x0101u);
}

// Cancellation-free roots of a*t^2 - 2*b*t + c - see slughorn::render::Sampler::_solveRoots().
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
	return vec2(
		(a.x * t1 - b.x * 2.0) * t1 + p12.x,
		(a.x * t2 - b.x * 2.0) * t2 + p12.x
	);
}

vec2 slug_SolveVertPoly(vec4 p12, vec2 p3) {
	vec2 a = p12.xy - p12.zw * 2.0 + p3;
	vec2 b = p12.xy - p12.zw;
	float rb = 0.5 / b.x;
	vec2 t = slug_SolveRoots(a.x, b.x, p12.x);
	float t1 = t.x;
	float t2 = t.y;
	if(abs(a.x) < 1.0 / 65536.0) { t1 = p12.x * rb; t2 = t1; }
	return vec2(
		(a.y * t1 - b.y * 2.0) * t1 + p12.y,
		(a.y * t2 - b.y * 2.0) * t2 + p12.y
	);
}

ivec2 slug_CalcBandLoc(ivec2 glyphLoc, uint offset) {
	ivec2 bandLoc = ivec2(glyphLoc.x + int(offset), glyphLoc.y);
	bandLoc.y += bandLoc.x >> TEX_WIDTH;
	bandLoc.x &= (1 << TEX_WIDTH) - 1;
	return bandLoc;
}

// 2026-08-30: re-derive a curve's second texel location the same way slug_CalcBandLoc()
// re-derives a band fetch's row, instead of flat-adding to curveLoc.x. Packing today always
// aligns a curve's 2 texels to start at an even X so they never straddle a row, making this a
// no-op -- but endpoint-shared curve packing (planned) removes that alignment, so a curve's
// second texel CAN legitimately land at the start of the next row.
ivec2 slug_CalcCurveLoc(ivec2 curveLoc, int offset) {
	ivec2 loc = ivec2(curveLoc.x + offset, curveLoc.y);
	loc.y += loc.x >> TEX_WIDTH;
	loc.x &= (1 << TEX_WIDTH) - 1;
	return loc;
}

float slug_CalcCoverage(float xcov, float ycov, float xwgt, float ywgt) {
	float coverage = max(
		abs(xcov * xwgt + ycov * ywgt) / max(xwgt + ywgt, 1.0 / 65536.0),
		min(abs(xcov), abs(ycov))
	);
	return clamp(coverage, 0.0, 1.0);
}

float slug_Render(vec2 renderCoord, vec4 bandTransform, ivec2 glyphLoc, ivec2 bandMax) {
	vec2 emsPerPixel = fwidth(renderCoord);
	vec2 pixelsPerEm = 1.0 / emsPerPixel;

	// O(1) band index via indirection tables (2 fetches per axis).
	int qY = clamp(int(renderCoord.y * bandTransform.y + bandTransform.w), 0, SLUG_INDIRECTION_SIZE - 1);
	int qX = clamp(int(renderCoord.x * bandTransform.x + bandTransform.z), 0, SLUG_INDIRECTION_SIZE - 1);
	int bandY = int(texelFetch(u_bandTexture, ivec2(glyphLoc.x + qY, glyphLoc.y), 0).r);
	int bandX = int(texelFetch(u_bandTexture, ivec2(glyphLoc.x + SLUG_INDIRECTION_SIZE + qX, glyphLoc.y), 0).r);

	// Horizontal bands -- headers at glyphLoc + 2*IS + bandY
	float xcov = 0.0, xwgt = 0.0;
	uvec2 hbandData = texelFetch(u_bandTexture, ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandY, glyphLoc.y), 0).xy;

	for(int ci = 0; ci < int(hbandData.x); ci++) {
		// Re-derive this fetch's row every iteration (instead of computing hbandLoc once
		// and flat-adding ci to its X) so a band's curve-index list can span more than one
		// texture row -- removes the old "count > texWidth" hard limit on shape complexity.
		// Costs up to ~10us on complex scenes vs. the single-fetch version; worth it.
		ivec2 hbandLoc = slug_CalcBandLoc(glyphLoc, hbandData.y + uint(ci));
		ivec2 curveLoc = ivec2(texelFetch(u_bandTexture, hbandLoc, 0).xy);
		vec4 p12 = texelFetch(u_curveTexture, curveLoc, 0) - vec4(renderCoord, renderCoord);

		// 2026-08-31: same re-derive-per-fetch pattern as slug_CalcBandLoc above, now applied to
		// a curve's own second texel -- needed once packTextures() (slughorn.cpp) started
		// sharing texels between connected curves (Lengyel's Slug convention: a chain of N
		// connected curves costs N+1 texels instead of 2N), which packs densely with no
		// per-curve row alignment. Adds a handful of ALU ops per curve iteration; negligible
		// next to the texture fetch and sqrt-based polynomial solve already in this loop.
		// Shipped as the default (no opt-in flag) -- lossless, and verified bit-identical
		// against the pre-sharing renderer at real CJK-atlas scale.
		vec2 p3 = texelFetch(u_curveTexture, slug_CalcCurveLoc(curveLoc, 1), 0).xy - renderCoord;

		if(max(max(p12.x, p12.z), p3.x) * pixelsPerEm.x < -0.5) break;

		uint code = slug_CalcRootCode(p12.y, p12.w, p3.y);
		if(code != 0u) {
			vec2 r = slug_SolveHorizPoly(p12, p3) * pixelsPerEm.x;
			if((code & 1u) != 0u) { xcov += clamp(r.x + 0.5, 0.0, 1.0); xwgt = max(xwgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0)); }
			if(code > 1u) { xcov -= clamp(r.y + 0.5, 0.0, 1.0); xwgt = max(xwgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0)); }
		}
	}

	// Vertical bands -- headers at glyphLoc + 2*IS + numHBands + bandX
	float ycov = 0.0, ywgt = 0.0;
	uvec2 vbandData = texelFetch(u_bandTexture, ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandMax.y + 1 + bandX, glyphLoc.y), 0).xy;

	for(int ci = 0; ci < int(vbandData.x); ci++) {
		// See the matching comment in the horizontal-band loop above.
		ivec2 vbandLoc = slug_CalcBandLoc(glyphLoc, vbandData.y + uint(ci));
		ivec2 curveLoc = ivec2(texelFetch(u_bandTexture, vbandLoc, 0).xy);
		vec4 p12 = texelFetch(u_curveTexture, curveLoc, 0) - vec4(renderCoord, renderCoord);

		// See the matching endpoint-sharing tradeoff comment in the horizontal-band loop above.
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

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
void main() {
	ivec2 glyphLoc = ivec2(v_shapeData.xy);
	ivec2 bandMax = ivec2(v_shapeData.zw);

	float fill = slug_Render(v_emCoord, v_bandTransform, glyphLoc, bandMax);

	if(fill < 0.001) discard;

	fragColor = vec4(v_color.rgb, fill * v_color.a);
}
)";

// ============================================================================
// GL helpers
// ============================================================================

static GLuint compileShader(GLenum type, const char* src) {
	GLuint s = glCreateShader(type);
	glShaderSource(s, 1, &src, nullptr);
	glCompileShader(s);

	GLint ok = 0;
	glGetShaderiv(s, GL_COMPILE_STATUS, &ok);

	if(!ok) {
		char log[2048];
		glGetShaderInfoLog(s, sizeof(log), nullptr, log);
		glDeleteShader(s);
		throw std::runtime_error(std::string("Shader compile error:\n") + log);
	}

	return s;
}

static GLuint linkProgram(GLuint vert, GLuint frag) {
	GLuint p = glCreateProgram();
	glAttachShader(p, vert);
	glAttachShader(p, frag);
	glLinkProgram(p);

	GLint ok = 0;
	glGetProgramiv(p, GL_LINK_STATUS, &ok);

	if(!ok) {
		char log[2048];
		glGetProgramInfoLog(p, sizeof(log), nullptr, log);
		glDeleteProgram(p);
		throw std::runtime_error(std::string("Program link error:\n") + log);
	}

	return p;
}

// Upload a slughorn::Atlas::TextureData buffer to a new GL texture.
// Returns the GL texture object name.
static GLuint uploadSlugTexture(const slughorn::Atlas::TextureData& td) {
	GLuint tex = 0;
	glGenTextures(1, &tex);
	glBindTexture(GL_TEXTURE_2D, tex);

	// Slug textures are fetched with texelFetch -- no filtering needed.
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

	if(td.format == slughorn::Atlas::TextureData::Format::RGBA32F) {
		glTexImage2D(
			GL_TEXTURE_2D, 0,
			GL_RGBA32F,
			(GLsizei)td.width, (GLsizei)td.height, 0,
			GL_RGBA, GL_FLOAT,
			td.bytes.data()
		);
	} else if(td.format == slughorn::Atlas::TextureData::Format::RGBA16F) {
		// Curve texture. Bytes are already packed as half-floats by Atlas::packTextures();
		// GL_HALF_FLOAT tells GL to upload them as-is, not to convert from GL_FLOAT.
		glTexImage2D(
			GL_TEXTURE_2D, 0,
			GL_RGBA16F,
			(GLsizei)td.width, (GLsizei)td.height, 0,
			GL_RGBA, GL_HALF_FLOAT,
			td.bytes.data()
		);
	} else if(td.format == slughorn::Atlas::TextureData::Format::RG16UI) {
		// Band texture (current default). B/A are never consumed by the shader (which only
		// ever reads .xy), so this is lossless, not a tradeoff like RGBA16F above.
		glTexImage2D(
			GL_TEXTURE_2D, 0,
			GL_RG16UI,
			(GLsizei)td.width, (GLsizei)td.height, 0,
			GL_RG_INTEGER, GL_UNSIGNED_SHORT,
			td.bytes.data()
		);
	} else {
		// RGBA16UI -- legacy band texture format, read-compat only (a .slug/.slugb serialized
		// before 2026-08-29 can still deserialize into this format).
		glTexImage2D(
			GL_TEXTURE_2D, 0,
			GL_RGBA16UI,
			(GLsizei)td.width, (GLsizei)td.height, 0,
			GL_RGBA_INTEGER, GL_UNSIGNED_SHORT,
			td.bytes.data()
		);
	}

	glBindTexture(GL_TEXTURE_2D, 0);
	return tex;
}

// ============================================================================
// Tiny orthographic matrix (column-major, matching GLSL mat4 layout)
//
// Maps [left,right] x [bottom,top] x [near,far] -> NDC.
// No GLM required.
// ============================================================================
static void orthoMatrix(
	float left, float right,
	float bottom, float top,
	float near_, float far_,
	float out[16]
) {
	std::memset(out, 0, 64);

	out[0] = 2.0f / (right - left);
	out[5] = 2.0f / (top - bottom);
	out[10] = -2.0f / (far_ - near_);
	out[12] = -(right + left) / (right - left);
	out[13] = -(top + bottom) / (top - bottom);
	out[14] = -(far_ + near_) / (far_ - near_);
	out[15] = 1.0f;
}

struct Vec3 {
	float x, y, z;
};

static Vec3 operator+(const Vec3& a, const Vec3& b) {
	return {a.x + b.x, a.y + b.y, a.z + b.z};
}

static Vec3 operator-(const Vec3& a, const Vec3& b) {
	return {a.x - b.x, a.y - b.y, a.z - b.z};
}

static Vec3 operator*(const Vec3& v, float s) {
	return {v.x * s, v.y * s, v.z * s};
}

static float dot(const Vec3& a, const Vec3& b) {
	return a.x * b.x + a.y * b.y + a.z * b.z;
}

static Vec3 cross(const Vec3& a, const Vec3& b) {
	return {
		a.y * b.z - a.z * b.y,
		a.z * b.x - a.x * b.z,
		a.x * b.y - a.y * b.x
	};
}

static Vec3 normalize(const Vec3& v) {
	const float len = std::sqrt(dot(v, v));

	if(len <= 1e-6f) return {0.0f, 0.0f, 0.0f};

	return {v.x / len, v.y / len, v.z / len};
}

static void perspectiveMatrix(float fovyRadians, float aspect, float near_, float far_, float out[16]) {
	std::memset(out, 0, 64);

	const float f = 1.0f / std::tan(fovyRadians * 0.5f);

	out[0] = f / aspect;
	out[5] = f;
	out[10] = (far_ + near_) / (near_ - far_);
	out[11] = -1.0f;
	out[14] = (2.0f * far_ * near_) / (near_ - far_);
}

static void lookAtMatrix(const Vec3& eye, const Vec3& center, const Vec3& up, float out[16]) {
	const Vec3 f = normalize(center - eye);
	const Vec3 s = normalize(cross(f, up));
	const Vec3 u = cross(s, f);

	std::memset(out, 0, 64);

	out[0] = s.x;
	out[1] = u.x;
	out[2] = -f.x;

	out[4] = s.y;
	out[5] = u.y;
	out[6] = -f.y;

	out[8] = s.z;
	out[9] = u.z;
	out[10] = -f.z;

	out[12] = -dot(s, eye);
	out[13] = -dot(u, eye);
	out[14] = dot(f, eye);
	out[15] = 1.0f;
}

static void multiplyMatrix4(const float a[16], const float b[16], float out[16]) {
	float tmp[16];

	for(int c = 0; c < 4; c++) {
		for(int r = 0; r < 4; r++) {
			tmp[c * 4 + r] =
				a[0 * 4 + r] * b[c * 4 + 0] +
				a[1 * 4 + r] * b[c * 4 + 1] +
				a[2 * 4 + r] * b[c * 4 + 2] +
				a[3 * 4 + r] * b[c * 4 + 3];
		}
	}

	std::memcpy(out, tmp, sizeof(tmp));
}

struct ViewState {
	Vec3 target = {2.0f, 0.3f, 0.0f};
	float yaw = 0.0f;
	float pitch = 0.0f;
	float distance = 5.0f;
	bool usePerspective = true;

	bool leftDragging = false;
	bool rightDragging = false;
	double lastCursorX = 0.0;
	double lastCursorY = 0.0;

	void reset() {
		target = {2.0f, 0.3f, 0.0f};
		yaw = 0.0f;
		pitch = 0.0f;
		distance = 5.0f;
		usePerspective = true;
		leftDragging = false;
		rightDragging = false;
		lastCursorX = 0.0;
		lastCursorY = 0.0;
	}
};

static Vec3 orbitCameraPosition(const ViewState& view) {
	const float cp = std::cos(view.pitch);
	const float sp = std::sin(view.pitch);
	const float sy = std::sin(view.yaw);
	const float cy = std::cos(view.yaw);

	return {
		view.target.x + view.distance * cp * sy,
		view.target.y + view.distance * sp,
		view.target.z + view.distance * cp * cy
	};
}

// ============================================================================
// Vertex layout (mirrors ShapeDrawable::compile() exactly)
//
// location 0: position vec3
// location 1: color vec4
// location 2: emCoord vec4 (.xy = em-space, .zw = true [0,1] UV)
// location 3: bandTransform vec4 (bandScaleX/Y, bandOffsetX/Y)
// location 4: shapeData vec4 (bandTexX/Y, bandMaxX/Y)
// location 5: effectId float (unused here, carried for forward-compat)
// ============================================================================
struct Vertex {
	float position[3];
	float color[4];
	float emCoord[4];
	float bandTransform[4];
	float shapeData[4];
	float effectId;
};

// ============================================================================
// GLFW callbacks
// ============================================================================

static void onGlfwError(int code, const char* msg) {
	std::fprintf(stderr, "GLFW error %d: %s\n", code, msg);
}

static void onKey(GLFWwindow* win, int key, int /*scancode*/, int action, int /*mods*/) {
	ViewState* view = static_cast<ViewState*>(glfwGetWindowUserPointer(win));

	if(key == GLFW_KEY_ESCAPE && action == GLFW_PRESS)
		glfwSetWindowShouldClose(win, GLFW_TRUE);

	if(!view || action != GLFW_PRESS) return;

	if(key == GLFW_KEY_P) view->usePerspective = !view->usePerspective;
	if(key == GLFW_KEY_R) view->reset();
}

static void onMouseButton(GLFWwindow* win, int button, int action, int /*mods*/) {
	ViewState* view = static_cast<ViewState*>(glfwGetWindowUserPointer(win));

	if(!view) return;

	if(button == GLFW_MOUSE_BUTTON_LEFT) view->leftDragging = (action == GLFW_PRESS);
	if(button == GLFW_MOUSE_BUTTON_RIGHT) view->rightDragging = (action == GLFW_PRESS);

	double x = 0.0, y = 0.0;
	glfwGetCursorPos(win, &x, &y);
	view->lastCursorX = x;
	view->lastCursorY = y;
}

static void onCursorPos(GLFWwindow* win, double x, double y) {
	ViewState* view = static_cast<ViewState*>(glfwGetWindowUserPointer(win));

	if(!view) return;

	const float dx = static_cast<float>(x - view->lastCursorX);
	const float dy = static_cast<float>(y - view->lastCursorY);

	view->lastCursorX = x;
	view->lastCursorY = y;

	if(view->leftDragging) {
		view->yaw -= dx * 0.01f;
		view->pitch += dy * 0.01f;

		const float limit = 1.45f;
		if(view->pitch > limit) view->pitch = limit;
		if(view->pitch < -limit) view->pitch = -limit;
	}

	if(view->rightDragging) {
		int fbW = 1, fbH = 1;
		glfwGetFramebufferSize(win, &fbW, &fbH);

		const Vec3 eye = orbitCameraPosition(*view);
		const Vec3 forward = normalize(view->target - eye);
		Vec3 right = normalize(cross(forward, {0.0f, 1.0f, 0.0f}));
		Vec3 up = normalize(cross(right, forward));

		if(dot(right, right) <= 1e-6f) right = {1.0f, 0.0f, 0.0f};
		if(dot(up, up) <= 1e-6f) up = {0.0f, 1.0f, 0.0f};

		float worldPerPixel = 0.0f;
		if(view->usePerspective) {
			const float fovy = 45.0f * 3.14159265f / 180.0f;
			worldPerPixel = 2.0f * view->distance * std::tan(fovy * 0.5f) / float(std::max(fbH, 1));
		} else {
			worldPerPixel = 2.0f * (0.5f / view->distance + 0.15f) / float(std::max(fbH, 1));
		}

		view->target = view->target - right * (dx * worldPerPixel) + up * (dy * worldPerPixel);
	}
}

static void onScroll(GLFWwindow* win, double /*xoffset*/, double yoffset) {
	ViewState* view = static_cast<ViewState*>(glfwGetWindowUserPointer(win));

	if(!view) return;

	const float zoom = std::exp(-static_cast<float>(yoffset) * 0.12f);
	view->distance *= zoom;

	if(view->distance < 0.35f) view->distance = 0.35f;
	if(view->distance > 1000000.0f) view->distance = 1000000.0f;
}

// ============================================================================
// main
// ============================================================================

struct RenderResources {
	bool glfwInitialized = false;
	GLFWwindow* window = nullptr;
	GLuint texCurve = 0;
	GLuint texBand = 0;
	GLuint vert = 0;
	GLuint frag = 0;
	GLuint program = 0;
	GLuint vao = 0;
	GLuint vbo = 0;
	GLuint ebo = 0;
	void* mesh = nullptr;

	~RenderResources() {
		if(vao) glDeleteVertexArrays(1, &vao);
		if(vbo) glDeleteBuffers(1, &vbo);
		if(ebo) glDeleteBuffers(1, &ebo);
		if(texCurve) glDeleteTextures(1, &texCurve);
		if(texBand) glDeleteTextures(1, &texBand);
		if(program) glDeleteProgram(program);
		if(vert) glDeleteShader(vert);
		if(frag) glDeleteShader(frag);
		if(window) glfwDestroyWindow(window);
		if(glfwInitialized) glfwTerminate();
		if(mesh) ss_mesh_destroy(mesh);
	}
};

static void captureFrame(const char* path, int width, int height) {
	std::vector<unsigned char> pixels(static_cast<size_t>(width) * height * 3);
	glPixelStorei(GL_PACK_ALIGNMENT, 1);
	glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, pixels.data());

	std::FILE* file = std::fopen(path, "wb");
	if(!file) throw std::runtime_error(std::string("failed to open capture: ") + path);
	std::fprintf(file, "P6\n%d %d\n255\n", width, height);
	for(int y = height - 1; y >= 0; --y) {
		const auto* row = pixels.data() + static_cast<size_t>(y) * width * 3;
		if(std::fwrite(row, 1, static_cast<size_t>(width) * 3, file) != static_cast<size_t>(width) * 3) {
			std::fclose(file);
			throw std::runtime_error(std::string("failed to write capture: ") + path);
		}
	}
	if(std::fclose(file) != 0) {
		throw std::runtime_error(std::string("failed to close capture: ") + path);
	}
}

int ss_glfw_run(
	void* atlasHandle, const SSLayersView layers, const char* title,
	int curvePrecision, const char* capturePath, int maxFrames) noexcept {
	ss_try(1,
		auto* atlas = slipstream::require_atlas(atlasHandle);
		if(curvePrecision != 16 && curvePrecision != 32)
			throw std::invalid_argument("curve precision must be 16 or 32");
		if(layers.count > 0 && !layers.data)
			throw std::invalid_argument("layers are null");
		if(maxFrames < 0) throw std::invalid_argument("max frames must not be negative");

		RenderResources resources;
		resources.mesh = ss_mesh_new(atlasHandle, layers.data, layers.count);
		if(!resources.mesh) throw std::runtime_error(slipstream::last_error);
		SSMeshDescriptor mesh = {};
		if(ss_mesh_descriptor(resources.mesh, &mesh) != 0)
			throw std::runtime_error(slipstream::last_error);
		if(mesh.vertex_count == 0)
			throw std::runtime_error("No geometry produced -- check Atlas build.");

		glfwSetErrorCallback(onGlfwError);
		if(!glfwInit()) throw std::runtime_error("glfwInit failed");
		resources.glfwInitialized = true;

		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
#ifdef __APPLE__
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
		if(capturePath) glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_FALSE);
#endif
		resources.window = glfwCreateWindow(800, 600, title ? title : "slughorn", nullptr, nullptr);
		if(!resources.window) throw std::runtime_error("glfwCreateWindow failed");

		ViewState view;
		std::printf("Controls:\n");
		std::printf("  Left drag   orbit\n");
		std::printf("  Right drag  pan\n");
		std::printf("  Wheel       zoom\n");
		std::printf("  P           toggle perspective/ortho\n");
		std::printf("  R           reset view\n");
		std::printf("  Esc         quit\n");

		glfwSetWindowUserPointer(resources.window, &view);
		glfwSetKeyCallback(resources.window, onKey);
		glfwSetMouseButtonCallback(resources.window, onMouseButton);
		glfwSetCursorPosCallback(resources.window, onCursorPos);
		glfwSetScrollCallback(resources.window, onScroll);
		glfwMakeContextCurrent(resources.window);
		glfwSwapInterval(1);
		if(!gladLoadGLLoader((GLADloadproc)glfwGetProcAddress))
			throw std::runtime_error("gladLoadGLLoader failed");
		std::printf("OpenGL %s\n", glGetString(GL_VERSION));

		resources.texCurve = uploadSlugTexture(atlas->getCurveTextureData());
		resources.texBand = uploadSlugTexture(atlas->getBandTextureData());
		resources.vert = compileShader(GL_VERTEX_SHADER, k_VertSrc);
		resources.frag = compileShader(GL_FRAGMENT_SHADER, k_FragSrc);
		resources.program = linkProgram(resources.vert, resources.frag);

		const GLint u_mvp = glGetUniformLocation(resources.program, "u_mvp");
		const GLint u_time = glGetUniformLocation(resources.program, "u_time");
		const GLint u_curveTexture = glGetUniformLocation(resources.program, "u_curveTexture");
		const GLint u_bandTexture = glGetUniformLocation(resources.program, "u_bandTexture");
		glUseProgram(resources.program);
		glUniform1i(u_curveTexture, 0);
		glUniform1i(u_bandTexture, 1);
		glUseProgram(0);

		glGenVertexArrays(1, &resources.vao);
		glGenBuffers(1, &resources.vbo);
		glGenBuffers(1, &resources.ebo);
		glBindVertexArray(resources.vao);
		glBindBuffer(GL_ARRAY_BUFFER, resources.vbo);
		glBufferData(GL_ARRAY_BUFFER,
			static_cast<GLsizeiptr>(mesh.vertex_count * sizeof(Vertex)),
			mesh.vertices, GL_STATIC_DRAW);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resources.ebo);
		glBufferData(GL_ELEMENT_ARRAY_BUFFER,
			static_cast<GLsizeiptr>(mesh.index_count * sizeof(uint16_t)),
			mesh.indices, GL_STATIC_DRAW);

		const GLsizei stride = static_cast<GLsizei>(sizeof(Vertex));
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, position));
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, color));
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, emCoord));
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, bandTransform));
		glEnableVertexAttribArray(4);
		glVertexAttribPointer(4, 4, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, shapeData));
		glEnableVertexAttribArray(5);
		glVertexAttribPointer(5, 1, GL_FLOAT, GL_FALSE, stride, (void*)offsetof(Vertex, effectId));
		glBindVertexArray(0);

		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glClearColor(0.15f, 0.15f, 0.15f, 1.0f);

		int frameCount = 0;
		while(!glfwWindowShouldClose(resources.window)) {
			glfwPollEvents();
			int fbW, fbH;
			glfwGetFramebufferSize(resources.window, &fbW, &fbH);
			glViewport(0, 0, fbW, fbH);
			glClear(GL_COLOR_BUFFER_BIT);

			const float aspect = (fbH > 0) ? (float)fbW / (float)fbH : 1.0f;
			const Vec3 eye = orbitCameraPosition(view);
			const Vec3 up = std::abs(std::cos(view.pitch)) < 0.05f
				? Vec3{0.0f, 0.0f, 1.0f}
				: Vec3{0.0f, 1.0f, 0.0f};
			float proj[16];
			if(view.usePerspective) {
				perspectiveMatrix(45.0f * 3.14159265f / 180.0f, aspect, 0.01f, 100.0f, proj);
			} else {
				const float margin = 0.15f;
				const float halfH = (0.5f + margin) / view.distance;
				const float halfW = halfH * aspect;
				orthoMatrix(-halfW, halfW, -halfH, halfH, -100.0f, 100.0f, proj);
			}
			float viewMatrix[16];
			lookAtMatrix(eye, view.target, up, viewMatrix);
			float mvp[16];
			multiplyMatrix4(proj, viewMatrix, mvp);
			const float t = (float)glfwGetTime();

			glUseProgram(resources.program);
			glUniformMatrix4fv(u_mvp, 1, GL_FALSE, mvp);
			glUniform1f(u_time, t);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, resources.texCurve);
			glActiveTexture(GL_TEXTURE1);
			glBindTexture(GL_TEXTURE_2D, resources.texBand);
			glBindVertexArray(resources.vao);
			glDrawElements(GL_TRIANGLES, static_cast<GLsizei>(mesh.index_count), GL_UNSIGNED_SHORT, nullptr);
			glBindVertexArray(0);
			glUseProgram(0);

			if(capturePath && frameCount == 0) captureFrame(capturePath, fbW, fbH);
			glfwSwapBuffers(resources.window);
			++frameCount;
			if(maxFrames > 0 && frameCount >= maxFrames)
				glfwSetWindowShouldClose(resources.window, GLFW_TRUE);
		}
		return 0;
	);
}

char* ss_read_stdin_text(void) noexcept {
	ss_try(nullptr,
		if(isatty(fileno(stdin))) return nullptr;
		std::string input(
			(std::istreambuf_iterator<char>(std::cin)),
			std::istreambuf_iterator<char>());
		char* result = static_cast<char*>(std::malloc(input.size() + 1));
		if(!result) throw std::bad_alloc();
		std::memcpy(result, input.c_str(), input.size() + 1);
		return result;
	);
}

void ss_free_stdin_text(char* text) noexcept {
	std::free(text);
}
