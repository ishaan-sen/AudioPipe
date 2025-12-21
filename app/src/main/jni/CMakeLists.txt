cmake_minimum_required(VERSION 3.22.1)

project("soundmaster_adb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message("Building Release...")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    message("Building Debug...")
    add_definitions(-DDEBUG)
endif ()

# Find BoringSSL - we'll use the system OpenSSL as a fallback
find_package(OpenSSL)

add_library(adb SHARED
        adb_pairing.cpp)

target_include_directories(adb PRIVATE ${OPENSSL_INCLUDE_DIR})
target_link_libraries(adb log ${OPENSSL_LIBRARIES})

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET adb POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libadb.so")
endif ()
