#pragma once

#include <cstddef>
#include <exception>
#include <stdexcept>

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
