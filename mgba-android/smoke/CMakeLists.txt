cmake_minimum_required(VERSION 3.22.1)
project(mgba_core_smoke LANGUAGES C)

set(MGBA_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../vendor/mgba")
set(MGBA_SESSION_DIR "${CMAKE_CURRENT_LIST_DIR}/../core/src/main/cpp")
set(TEST_ASSET_DIR "${CMAKE_CURRENT_LIST_DIR}/../core/src/androidTest/assets")
set(LIBMGBA_ONLY ON CACHE BOOL "Build only libmGBA" FORCE)
set(M_CORE_GBA ON CACHE BOOL "Build the Game Boy Advance core" FORCE)
set(M_CORE_GB ON CACHE BOOL "Build the Game Boy core" FORCE)
set(BUILD_LTO OFF CACHE BOOL "Keep the smoke build deterministic" FORCE)
add_subdirectory("${MGBA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

function(garnacha_strict_c target)
    if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(${target} PRIVATE -Wall -Wextra -Werror)
    endif()
endfunction()

option(GARNACHA_SANITIZERS "Enable ASan and UBSan for product host tests" OFF)

function(garnacha_sanitize target)
    if(GARNACHA_SANITIZERS AND CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(${target} PRIVATE
            -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(${target} PRIVATE -fsanitize=address,undefined)
    endif()
endfunction()

add_executable(mgba-core-smoke mgba_core_smoke.c)
target_link_libraries(mgba-core-smoke PRIVATE mgba)

add_executable(mgba-core-benchmark mgba_core_benchmark.c)
target_link_libraries(mgba-core-benchmark PRIVATE mgba)

add_library(garnacha-mgba-session STATIC "${MGBA_SESSION_DIR}/mgba_session.c")
target_include_directories(garnacha-mgba-session PUBLIC "${MGBA_SESSION_DIR}")
target_link_libraries(garnacha-mgba-session PRIVATE mgba)
garnacha_strict_c(garnacha-mgba-session)
garnacha_sanitize(garnacha-mgba-session)

add_executable(mgba-session-test mgba_session_test.c)
target_link_libraries(mgba-session-test PRIVATE garnacha-mgba-session mgba)
garnacha_strict_c(mgba-session-test)
garnacha_sanitize(mgba-session-test)

enable_testing()
add_test(NAME mgba-core-smoke COMMAND mgba-core-smoke)
add_test(NAME mgba-session-test COMMAND mgba-session-test
    "${TEST_ASSET_DIR}/hello.gba"
    "${TEST_ASSET_DIR}/hello.gb"
    "${TEST_ASSET_DIR}/sram.gba")
