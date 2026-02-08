cmake_minimum_required(VERSION 3.20)
project(voxswap-box C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

# Strict compiler warnings
add_compile_options(
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -Wshadow
    -Wformat=2
    -Wconversion
    -Wnull-dereference
    -Wdouble-promotion
)

# Debug/Release flags
set(CMAKE_C_FLAGS_DEBUG "-g -O0 -DDEBUG")
set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG")

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Debug)
endif()

# Find dependencies (optional — only available on target with full stack)
find_package(PkgConfig QUIET)
if(PkgConfig_FOUND)
    pkg_check_modules(PIPEWIRE IMPORTED_TARGET libpipewire-0.3)
    pkg_check_modules(BLUEZ IMPORTED_TARGET bluez)
endif()

# Sources
set(SOURCES
    src/main.c
    src/wifi-hotspot.c
    src/stream-receiver.c
    src/audio-mixer.c
    src/auracast-broadcast.c
    src/settings-server.c
)

add_executable(voxswap-box ${SOURCES})

target_include_directories(voxswap-box PRIVATE src)

# Link dependencies if found
if(PIPEWIRE_FOUND)
    target_link_libraries(voxswap-box PRIVATE PkgConfig::PIPEWIRE)
    target_compile_definitions(voxswap-box PRIVATE HAS_PIPEWIRE)
endif()

if(BLUEZ_FOUND)
    target_link_libraries(voxswap-box PRIVATE PkgConfig::BLUEZ)
    target_compile_definitions(voxswap-box PRIVATE HAS_BLUEZ)
endif()

target_link_libraries(voxswap-box PRIVATE pthread)

# Install
install(TARGETS voxswap-box RUNTIME DESTINATION bin)
install(DIRECTORY config/ DESTINATION etc/voxswap)
