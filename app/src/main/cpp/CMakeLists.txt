cmake_minimum_required(VERSION 3.18.1)
project("v4l2usbcam")

add_library(v4l2_camera SHARED
        v4l2_camera.cpp)

# 加入 turbojpeg.h 的 include 路徑
include_directories(${CMAKE_SOURCE_DIR}/libjpeg-turbo/include)

# 指定 libturbojpeg.a 路徑（記得檔案存在）
add_library(turbojpeg STATIC IMPORTED)
set_target_properties(turbojpeg PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/libjpeg-turbo/lib/libturbojpeg.a)

# 加入 log 函式庫
find_library(log-lib log)

target_link_libraries(v4l2_camera
        turbojpeg
        ${log-lib})

target_compile_definitions(v4l2_camera PUBLIC IS_CLONE=${IS_CLONE})

