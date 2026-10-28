cmake_minimum_required(VERSION 3.16)
project(slipstream LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT SLUGHORN_HOME)
  if(DEFINED ENV{SLUGHORN_HOME})
    set(SLUGHORN_HOME "$ENV{SLUGHORN_HOME}")
  else()
    set(SLUGHORN_HOME "$ENV{HOME}/src/slughorn")
  endif()
endif()
set(SLUGHORN_HOME "${SLUGHORN_HOME}" CACHE PATH "Path to the Slughorn checkout")
message(STATUS "Slipstream: using Slughorn at ${SLUGHORN_HOME}")

if(NOT EXISTS "${SLUGHORN_HOME}/slughorn/slughorn.cpp")
  message(FATAL_ERROR "Slughorn not found at ${SLUGHORN_HOME}; set SLUGHORN_HOME to a Slughorn checkout")
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(FREETYPE REQUIRED IMPORTED_TARGET freetype2)

set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
include(FetchContent)
FetchContent_Declare(
  glad
  GIT_REPOSITORY https://github.com/Dav1dde/glad.git
  GIT_TAG v0.1.36
  GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(glad)

FetchContent_Declare(
  glfw
  GIT_REPOSITORY https://github.com/glfw/glfw.git
  GIT_TAG 3.4
  GIT_SHALLOW TRUE)
set(GLFW_BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GLFW_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(glfw)

find_path(NLOHMANN_JSON_INCLUDE_DIR nlohmann/json.hpp
  PATHS "${SLUGHORN_HOME}/ext/json/include" /opt/homebrew/include /usr/local/include
  NO_DEFAULT_PATH)
if(NOT NLOHMANN_JSON_INCLUDE_DIR)
  message(FATAL_ERROR "nlohmann/json.hpp not found; initialize Slughorn's ext/json submodule or install nlohmann-json")
endif()

add_library(slipstream SHARED
  atlas.cpp
  shapes.cpp
  mesh.cpp
  path.cpp
  canvas.cpp
  freetype.cpp
  glfw_renderer.cpp
  reactive_renderer.cpp
  "${SLUGHORN_HOME}/slughorn/slughorn.cpp")
target_include_directories(slipstream PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}"
  "${SLUGHORN_HOME}"
  "${NLOHMANN_JSON_INCLUDE_DIR}")
target_link_libraries(slipstream PRIVATE PkgConfig::FREETYPE glad glfw)
target_compile_definitions(slipstream PRIVATE GLAD_GLAPI_EXPORT)

set_target_properties(slipstream PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
  PREFIX "lib"
  OUTPUT_NAME "slipstream")
foreach(config IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
  set_target_properties(slipstream PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY_${config} "${CMAKE_CURRENT_SOURCE_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_${config} "${CMAKE_CURRENT_SOURCE_DIR}")
endforeach()

add_executable(slipstream-parity-oracle
  parity_oracle.cpp
  "${SLUGHORN_HOME}/slughorn/slughorn.cpp")
target_include_directories(slipstream-parity-oracle PRIVATE
  "${SLUGHORN_HOME}"
  "${NLOHMANN_JSON_INCLUDE_DIR}")
target_link_libraries(slipstream-parity-oracle PRIVATE PkgConfig::FREETYPE glad glfw)
target_compile_definitions(slipstream-parity-oracle PRIVATE GLAD_GLAPI_EXPORT)

set_property(SOURCE "${SLUGHORN_HOME}/example/slughorn-example-glfw.cpp"
  APPEND PROPERTY COMPILE_DEFINITIONS SLUGHORN_FREETYPE_IMPLEMENTATION)
add_executable(slughorn-example-glfw
  "${SLUGHORN_HOME}/example/slughorn-example-glfw.cpp"
  "${SLUGHORN_HOME}/slughorn/slughorn.cpp")
target_include_directories(slughorn-example-glfw PRIVATE
  "${SLUGHORN_HOME}"
  "${NLOHMANN_JSON_INCLUDE_DIR}")
target_link_libraries(slughorn-example-glfw PRIVATE PkgConfig::FREETYPE glad glfw)
target_compile_definitions(slughorn-example-glfw PRIVATE GLAD_GLAPI_EXPORT)
