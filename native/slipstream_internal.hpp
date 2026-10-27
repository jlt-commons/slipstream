#pragma once

#include <cstddef>
#include <cstdint>
#include <exception>
#include <stdexcept>
#include <vector>

#include "slughorn/slughorn.hpp"

namespace slipstream {

inline constexpr std::size_t last_error_capacity = 1024;
inline thread_local char last_error[last_error_capacity] = {};

inline void clear_error() noexcept {
  last_error[0] = '\0';
}

inline void set_error(const char* message) noexcept {
  if(!message) {
    message = "unknown native exception";
  }

  std::size_t length = 0;
  while(length + 1 < last_error_capacity && message[length] != '\0') {
    last_error[length] = message[length];
    ++length;
  }
  last_error[length] = '\0';
}

inline slughorn::Atlas* require_atlas(void* handle) {
  if(!handle) {
    throw std::invalid_argument("atlas handle is null");
  }
  return static_cast<slughorn::Atlas*>(handle);
}

std::vector<slughorn::Layer> layout_text(
  slughorn::Atlas& atlas, const uint32_t* codepoints, std::size_t count,
  float font_size, float x, float y, slughorn::Color color,
  const slughorn::FontMetrics& metrics, uint32_t anchor_y, uint32_t align_x,
  uint8_t mask);

}

#define ss_try(failure, ...)                                                   \
  do {                                                                         \
    slipstream::clear_error();                                                  \
    try {                                                                       \
      __VA_ARGS__                                                               \
    } catch(const std::exception& error) {                                      \
      slipstream::set_error(error.what());                                      \
      return failure;                                                           \
    } catch(...) {                                                              \
      slipstream::set_error("unknown native exception");                       \
      return failure;                                                           \
    }                                                                           \
  } while(false)
