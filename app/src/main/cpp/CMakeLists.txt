cmake_minimum_required(VERSION 3.22.1)
project(automation_studio_native LANGUAGES C CXX)

add_library(
    automation_studio_native
    SHARED
    native-lib.cpp
)

target_compile_features(automation_studio_native PRIVATE cxx_std_20)
target_compile_options(automation_studio_native PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fvisibility=hidden
)

find_library(android-log log)
target_link_libraries(automation_studio_native PRIVATE ${android-log})
