cmake_minimum_required(VERSION 3.22.1)
project("shield_core")

add_library(
    shield_core
    SHARED
    shield_core.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    shield_core
    ${log-lib}
)
