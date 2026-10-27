#pragma once

#include <exception>
#include <stdexcept>
#include <string>

#include "slughorn/slughorn.hpp"

namespace slipstream {

inline thread_local std::string last_error;

inline slughorn::Atlas* require_atlas(void* handle) {
  if(!handle) {
    throw std::invalid_argument("atlas handle is null");
  }
  return static_cast<slughorn::Atlas*>(handle);
}

}

#define ss_try(failure, ...)                                                   \
  do {                                                                         \
    slipstream::last_error.clear();                                             \
    try {                                                                       \
      __VA_ARGS__                                                               \
    } catch(const std::exception& error) {                                      \
      slipstream::last_error = error.what();                                    \
      return failure;                                                           \
    } catch(...) {                                                              \
      slipstream::last_error = "unknown native exception";                     \
      return failure;                                                           \
    }                                                                           \
  } while(false)
