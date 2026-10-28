#include <glad/glad.h>
#include <GLFW/glfw3.h>

static GLFWwindow* parityCreateWindow(
	int width, int height, const char* title, GLFWmonitor* monitor, GLFWwindow* share);
static void paritySwapBuffers(GLFWwindow* window);
static void parityPollEvents();
static GLFWkeyfun paritySetKeyCallback(GLFWwindow* window, GLFWkeyfun callback);
static GLFWmousebuttonfun paritySetMouseButtonCallback(GLFWwindow* window, GLFWmousebuttonfun callback);
static GLFWcursorposfun paritySetCursorPosCallback(GLFWwindow* window, GLFWcursorposfun callback);
static GLFWscrollfun paritySetScrollCallback(GLFWwindow* window, GLFWscrollfun callback);

#define SLUGHORN_FREETYPE_IMPLEMENTATION
#define main upstream_example_main
#define glfwCreateWindow parityCreateWindow
#define glfwSwapBuffers paritySwapBuffers
#define glfwPollEvents parityPollEvents
#define glfwSetKeyCallback paritySetKeyCallback
#define glfwSetMouseButtonCallback paritySetMouseButtonCallback
#define glfwSetCursorPosCallback paritySetCursorPosCallback
#define glfwSetScrollCallback paritySetScrollCallback
#include "example/slughorn-example-glfw.cpp"
#undef glfwSetScrollCallback
#undef glfwSetCursorPosCallback
#undef glfwSetMouseButtonCallback
#undef glfwSetKeyCallback
#undef glfwPollEvents
#undef glfwSwapBuffers
#undef glfwCreateWindow
#undef main

#include <filesystem>
#include <fstream>

namespace {

int swapCount = 0;

void captureFrame(const char* path, int width, int height) {
	if(width != 800 || height != 600)
		throw std::runtime_error("parity capture must be exactly 800x600");
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
	if(std::fclose(file) != 0)
		throw std::runtime_error(std::string("failed to close capture: ") + path);
}

void writeBytes(const std::filesystem::path& path, const void* data, size_t size) {
	std::ofstream output(path, std::ios::binary | std::ios::trunc);
	if(!output) throw std::runtime_error("failed to open output: " + path.string());
	output.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
	if(!output) throw std::runtime_error("failed to write output: " + path.string());
}

void writeTexture(const std::filesystem::path& directory, const char* name,
	const slughorn::Atlas::TextureData& texture) {
	std::ofstream descriptor(directory / (std::string(name) + ".desc"), std::ios::trunc);
	descriptor << texture.width << ' ' << texture.height << ' ' << texture.depth << ' '
		<< static_cast<int>(texture.format) << ' ' << texture.bytes.size() << '\n';
	if(!descriptor) throw std::runtime_error("failed to write texture descriptor");
	writeBytes(directory / (std::string(name) + ".bin"), texture.bytes.data(), texture.bytes.size());
}

int dataMain(int argc, char** argv) {
	if(argc != 5) {
		std::fprintf(stderr, "Usage: %s --data <font.ttf> <16|32> <text> <output-directory>\n", argv[0]);
		return 1;
	}

	const std::string precision = argv[2];
	if(precision != "16" && precision != "32") {
		std::fprintf(stderr, "Invalid precision: %s (expected 16 or 32)\n", precision.c_str());
		return 1;
	}

	const std::string text = argv[3];
	const std::filesystem::path directory = argv[4];
	std::filesystem::create_directories(directory);

	slughorn::Atlas atlas;
	if(precision == "16")
		atlas.setCurveTextureFormat(slughorn::Atlas::TextureData::Format::RGBA16F);

	std::vector<uint32_t> codepoints;
	for(unsigned char ch : text) codepoints.push_back(static_cast<uint32_t>(ch));

	slughorn::freetype::LoadConfig config;
	const size_t loaded = slughorn::freetype::loadFontGlyphs(argv[1], codepoints, atlas, &config);
	if(loaded == 0) {
		std::fprintf(stderr, "Failed to load any glyphs from: %s\n", argv[1]);
		return 1;
	}
	atlas.build();

	std::vector<slughorn::Layer> layers;
	const float fontSize = 1.0f;
	float cursorX = 0.0f;
	for(unsigned char ch : text) {
		const uint32_t key = static_cast<uint32_t>(ch);
		const auto shape = atlas.getShape(key);
		if(!shape) {
			cursorX += 0.5f * fontSize;
			continue;
		}
		if(float(shape->width) < 1e-6f || float(shape->height) < 1e-6f) {
			cursorX += float(shape->advance) * fontSize;
			continue;
		}
		slughorn::Layer layer;
		layer.key = key;
		layer.color = {1.0f, 1.0f, 1.0f, 1.0f};
		layer.transform = slughorn::Transform{.x = cv(cursorX / fontSize), .y = 0_cv};
		layer.scale = cv(fontSize);
		layers.push_back(layer);
		cursorX += float(shape->advance) * fontSize;
	}

	std::vector<Vertex> vertices;
	std::vector<unsigned short> indices;
	buildMesh(atlas, layers, vertices, indices);
	if(vertices.empty()) {
		std::fprintf(stderr, "No geometry produced -- check Atlas build.\n");
		return 1;
	}

	writeTexture(directory, "curve", atlas.getCurveTextureData());
	writeTexture(directory, "band", atlas.getBandTextureData());
	std::ofstream descriptor(directory / "mesh.desc", std::ios::trunc);
	descriptor << vertices.size() << ' ' << indices.size() << ' ' << sizeof(Vertex) << '\n';
	if(!descriptor) throw std::runtime_error("failed to write mesh descriptor");
	writeBytes(directory / "vertices.bin", vertices.data(), vertices.size() * sizeof(Vertex));
	writeBytes(directory / "indices.bin", indices.data(), indices.size() * sizeof(unsigned short));
	return 0;
}

}

static GLFWwindow* activeWindow = nullptr;
static GLFWscrollfun registeredScrollCallback = nullptr;

static bool parityAutomatedCapture() {
	return std::getenv("SLIPSTREAM_PARITY_CAPTURE")
		|| std::getenv("SLIPSTREAM_PARITY_CAPTURE_DIR")
		|| std::getenv("SLIPSTREAM_PARITY_CONTROLS");
}

static GLFWkeyfun paritySetKeyCallback(GLFWwindow* window, GLFWkeyfun callback) {
	if(parityAutomatedCapture()) return nullptr;
	return glfwSetKeyCallback(window, callback);
}

static GLFWmousebuttonfun paritySetMouseButtonCallback(GLFWwindow* window, GLFWmousebuttonfun callback) {
	if(parityAutomatedCapture()) return nullptr;
	return glfwSetMouseButtonCallback(window, callback);
}

static GLFWcursorposfun paritySetCursorPosCallback(GLFWwindow* window, GLFWcursorposfun callback) {
	if(parityAutomatedCapture()) return nullptr;
	return glfwSetCursorPosCallback(window, callback);
}

static GLFWscrollfun paritySetScrollCallback(GLFWwindow* window, GLFWscrollfun callback) {
	if(parityAutomatedCapture()) {
		registeredScrollCallback = nullptr;
		return nullptr;
	}
	registeredScrollCallback = callback;
	return glfwSetScrollCallback(window, callback);
}

static void applyParityControls(int frame, GLFWwindow* window, ViewState* view) {
	if(!view) return;
	switch(frame) {
		case 1:
		case 2:
			onKey(window, GLFW_KEY_P, 0, GLFW_PRESS, 0);
			break;
		case 3:
			onMouseButton(window, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);
			view->lastCursorX = 100.0;
			view->lastCursorY = 100.0;
			onCursorPos(window, 130.0, 110.0);
			onMouseButton(window, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0);
			break;
		case 4:
			onMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS, 0);
			view->lastCursorX = 100.0;
			view->lastCursorY = 100.0;
			onCursorPos(window, 120.0, 115.0);
			onMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT, GLFW_RELEASE, 0);
			break;
		case 5:
			onScroll(window, 0.0, 1.0);
			break;
		case 6:
			onKey(window, GLFW_KEY_R, 0, GLFW_PRESS, 0);
			break;
	}
}

static GLFWwindow* parityCreateWindow(
	int width, int height, const char* title, GLFWmonitor* monitor, GLFWwindow* share) {
#ifdef __APPLE__
	if(std::getenv("SLIPSTREAM_PARITY_CAPTURE") || std::getenv("SLIPSTREAM_PARITY_CAPTURE_DIR"))
		glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_FALSE);
#endif
	activeWindow = glfwCreateWindow(width, height, title, monitor, share);
	return activeWindow;
}

static void parityPollEvents() {
	glfwPollEvents();
	if(activeWindow && swapCount == 0 && std::getenv("SLIPSTREAM_PARITY_INJECT_SCROLL") && registeredScrollCallback) {
		registeredScrollCallback(activeWindow, 0.0, -0.2);
		std::fprintf(stderr, "SLIPSTREAM_PARITY_SCROLL_CALLBACK\n");
	}
	if(activeWindow && std::getenv("SLIPSTREAM_PARITY_CONTROLS")) {
		auto* view = static_cast<ViewState*>(glfwGetWindowUserPointer(activeWindow));
		applyParityControls(swapCount, activeWindow, view);
	}
}

static void paritySwapBuffers(GLFWwindow* window) {
	int width = 0, height = 0;
	glfwGetFramebufferSize(window, &width, &height);
	if(const char* capture = std::getenv("SLIPSTREAM_PARITY_CAPTURE"); capture && swapCount == 0)
		captureFrame(capture, width, height);
	if(const char* directory = std::getenv("SLIPSTREAM_PARITY_CAPTURE_DIR")) {
		const auto path = std::filesystem::path(directory) / (std::to_string(swapCount) + ".ppm");
		captureFrame(path.string().c_str(), width, height);
	}
	glfwSwapBuffers(window);
	++swapCount;
	if(const char* frames = std::getenv("SLIPSTREAM_PARITY_MAX_FRAMES")) {
		const int maximum = std::atoi(frames);
		if(maximum > 0 && swapCount >= maximum) glfwSetWindowShouldClose(window, GLFW_TRUE);
	}
}

int main(int argc, char** argv) {
	try {
		if(argc > 1 && std::string(argv[1]) == "--data") return dataMain(argc - 1, argv + 1);
		return upstream_example_main(argc, argv);
	} catch(const std::exception& error) {
		std::fprintf(stderr, "%s\n", error.what());
		return 1;
	}
}
