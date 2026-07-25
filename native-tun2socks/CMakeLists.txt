cmake_minimum_required(VERSION 3.22.1)
project(supertunnel_tun2socks C ASM)

# Android's debug variant otherwise compiles the forwarding data plane with no
# optimisation. Keep Java debugability while always building tun2socks as a
# production-grade packet forwarder.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")

# Build our app-owned Go SSH/SOCKS core for the same ABI as this JNI library.
# It replaces the Java SSH forwarding path; no third-party app binary is used.
find_program(GO_EXECUTABLE go REQUIRED)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GO_ARCH arm64)
    set(GO_TRIPLE aarch64-linux-android)
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(GO_ARCH amd64)
    set(GO_TRIPLE x86_64-linux-android)
else()
    message(FATAL_ERROR "Unsupported Go Android ABI: ${ANDROID_ABI}")
endif()
set(GO_CC ${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/${GO_TRIPLE}26-clang)
set(GOJNI_OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/libgojni.so)
add_custom_command(
    OUTPUT ${GOJNI_OUTPUT}
    COMMAND ${CMAKE_COMMAND} -E env
        GOOS=android GOARCH=${GO_ARCH} CGO_ENABLED=1 CC=${GO_CC}
        ${GO_EXECUTABLE} build -buildmode=c-shared -o ${GOJNI_OUTPUT} .
    WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}/../go-native
    DEPENDS ${CMAKE_CURRENT_LIST_DIR}/../go-native/go.mod
            ${CMAKE_CURRENT_LIST_DIR}/../go-native/go.sum
            ${CMAKE_CURRENT_LIST_DIR}/../go-native/gojni.go)
add_custom_target(gojni_build DEPENDS ${GOJNI_OUTPUT})
add_library(gojni SHARED IMPORTED GLOBAL)
set_target_properties(gojni PROPERTIES IMPORTED_LOCATION ${GOJNI_OUTPUT})

set(HEV_ROOT ${CMAKE_CURRENT_LIST_DIR}/../third_party/hev-socks5-tunnel)
file(GLOB_RECURSE HEV_TUNNEL_SOURCES CONFIGURE_DEPENDS ${HEV_ROOT}/src/*.c)
file(GLOB_RECURSE HEV_TASK_SOURCES CONFIGURE_DEPENDS
    ${HEV_ROOT}/third-part/hev-task-system/src/*.c
    ${HEV_ROOT}/third-part/hev-task-system/src/*.S)
file(GLOB_RECURSE HEV_LWIP_SOURCES CONFIGURE_DEPENDS ${HEV_ROOT}/third-part/lwip/src/*.c)
file(GLOB_RECURSE HEV_YAML_SOURCES CONFIGURE_DEPENDS ${HEV_ROOT}/third-part/yaml/src/*.c)
# The upstream Android JNI wrapper hardcodes a different Java package. The
# app-owned wrapper below exposes the existing NativeTun2Socks Kotlin API.
list(FILTER HEV_TUNNEL_SOURCES EXCLUDE REGEX ".*/hev-jni\\.c$")
list(FILTER HEV_TUNNEL_SOURCES EXCLUDE REGEX ".*/hev-tunnel-(freebsd|macos|netbsd|windows)\\.c$")
list(FILTER HEV_TUNNEL_SOURCES EXCLUDE REGEX ".*/hev-wintun\\.c$")

set(HEV_INCLUDE_DIRS
    ${HEV_ROOT}/src ${HEV_ROOT}/src/misc ${HEV_ROOT}/src/core/src
    ${HEV_ROOT}/third-part/yaml/include
    ${HEV_ROOT}/third-part/lwip/src/include ${HEV_ROOT}/third-part/lwip/src/ports/include
    ${HEV_ROOT}/third-part/hev-task-system/include ${HEV_ROOT}/third-part/hev-task-system/src)
set(HEV_DEFINITIONS
    ANDROID FD_SET_DEFINED SOCKLEN_T_DEFINED ENABLE_LIBRARY COMMIT_ID=\"embedded\"
    ENABLE_STACK_OVERFLOW_DETECTION ENABLE_MEMALLOC_SLICE ENABLE_IO_SPLICE_SYSCALL
    CONFIG_STACK_BACKEND=STACK_MMAP CONFIG_STACK_OVERFLOW_DETECTION=1
    CONFIG_MEMALLOC_SLICE_ALIGN=16 CONFIG_MEMALLOC_SLICE_MAX_SIZE=4096
    CONFIG_MEMALLOC_SLICE_MAX_COUNT=1000 CONFIG_SCHED_CLOCK=CLOCK_NONE
    YAML_VERSION_MAJOR=0 YAML_VERSION_MINOR=2 YAML_VERSION_PATCH=5 YAML_VERSION_STRING=\"0.2.5\")
add_library(hev_yaml STATIC ${HEV_YAML_SOURCES})
add_library(hev_lwip STATIC ${HEV_LWIP_SOURCES})
add_library(hev_task STATIC ${HEV_TASK_SOURCES})
foreach(target hev_yaml hev_lwip hev_task)
    target_include_directories(${target} PRIVATE ${HEV_INCLUDE_DIRS})
    target_compile_definitions(${target} PRIVATE ${HEV_DEFINITIONS})
endforeach()

add_library(tun2socks_jni SHARED hev_tun2socks_jni.c gojni_bridge.c ${HEV_TUNNEL_SOURCES})
add_dependencies(tun2socks_jni gojni_build)
target_include_directories(tun2socks_jni PRIVATE ${HEV_INCLUDE_DIRS})
target_compile_definitions(tun2socks_jni PRIVATE ${HEV_DEFINITIONS})
target_link_libraries(tun2socks_jni gojni hev_yaml hev_lwip hev_task log)
target_compile_options(tun2socks_jni PRIVATE -O3 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fvisibility=hidden)
target_link_options(tun2socks_jni PRIVATE -Wl,--export-dynamic,-z,relro,-z,now,-z,max-page-size=16384)
