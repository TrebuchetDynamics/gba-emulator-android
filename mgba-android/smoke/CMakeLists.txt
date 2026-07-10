cmake_minimum_required(VERSION 3.22.1)
project(mgba_core_smoke LANGUAGES C)

set(MGBA_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../vendor/mgba")
set(LIBMGBA_ONLY ON CACHE BOOL "Build only libmGBA" FORCE)
set(M_CORE_GBA ON CACHE BOOL "Build the Game Boy Advance core" FORCE)
set(M_CORE_GB OFF CACHE BOOL "Do not build the Game Boy core" FORCE)
set(BUILD_LTO OFF CACHE BOOL "Keep the smoke build deterministic" FORCE)
add_subdirectory("${MGBA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

add_executable(mgba-core-smoke mgba_core_smoke.c)
target_link_libraries(mgba-core-smoke PRIVATE mgba)

enable_testing()
add_test(NAME mgba-core-smoke COMMAND mgba-core-smoke)
