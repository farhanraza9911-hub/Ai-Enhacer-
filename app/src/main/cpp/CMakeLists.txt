cmake_minimum_required(VERSION 3.22.1)
project(enhancer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt ncnn (Vulkan) -- har ABI ka apna folder: ncnn/<abi>/
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(enhancer SHARED enhancer_jni.cpp look.cpp)

target_compile_options(enhancer PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections)

# -static-openmp: libomp.so alag se ship karne ki zaroorat nahi
target_link_options(enhancer PRIVATE
    -Wl,--gc-sections
    -static-openmp)

target_link_libraries(enhancer
    ncnn
    jnigraphics
    android
    log)
