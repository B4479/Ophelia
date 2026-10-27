cmake_minimum_required(VERSION 3.22.1)
project(localllm CXX C)

# Debug 构建也开优化，否则端侧推理慢到不可用
set(CMAKE_C_FLAGS_DEBUG "-O2 -g")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只保留纯 CPU 推理，关掉示例/测试/服务端，减小体积和编译时间
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

# Gradle 默认 Debug 无优化，这里对所有配置追加 -O3（否则端侧推理极慢）
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_subdirectory(llama.cpp build-llama)
# 多模态（视觉）支持
add_subdirectory(llama.cpp/tools/mtmd build-mtmd)

find_library(log-lib log)

add_library(llama_bridge SHARED llama_bridge.cpp vision_bridge.cpp)
target_include_directories(llama_bridge PRIVATE
        llama.cpp/include
        llama.cpp/ggml/include
        llama.cpp/tools/mtmd)
target_link_libraries(llama_bridge PRIVATE mtmd llama ${log-lib} android)
