cmake_minimum_required(VERSION 3.22.1)
project(mgba_android LANGUAGES C)

set(MGBA_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../vendor/mgba")
if(NOT EXISTS "${MGBA_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "mGBA submodule is missing. Run: git submodule update --init --depth 1")
endif()

set(LIBMGBA_ONLY ON CACHE BOOL "Build only libmGBA" FORCE)
set(M_CORE_GBA ON CACHE BOOL "Build the Game Boy Advance core" FORCE)
set(M_CORE_GB OFF CACHE BOOL "Do not build the Game Boy core" FORCE)
set(BUILD_LTO OFF CACHE BOOL "Keep the integration build deterministic" FORCE)
add_subdirectory("${MGBA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

add_library(mgba-android SHARED src/main/cpp/mgba_android.c)
target_link_libraries(mgba-android PRIVATE mgba android log)
