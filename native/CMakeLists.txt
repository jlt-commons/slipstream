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
  "${SLUGHORN_HOME}/slughorn/slughorn.cpp")
target_include_directories(slipstream PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}"
  "${SLUGHORN_HOME}"
  "${NLOHMANN_JSON_INCLUDE_DIR}")
target_link_libraries(slipstream PRIVATE PkgConfig::FREETYPE)

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
