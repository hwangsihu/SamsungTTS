# Minimal package config for the header-only Boost that tools/fetch_deps.py puts in
# third_party/boost. dynarmic only needs Boost's ICL and Variant headers, and CMake 4
# dropped the old FindBoost module, so find_package(Boost CONFIG) resolves to this.
get_filename_component(_smt_boost "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/boost" ABSOLUTE)

if(NOT EXISTS "${_smt_boost}/boost/version.hpp")
    set(Boost_FOUND FALSE)
    set(Boost_NOT_FOUND_MESSAGE "third_party/boost is missing; run: python -I tools/fetch_deps.py")
    return()
endif()

foreach(_t Boost::boost Boost::headers)
    if(NOT TARGET ${_t})
        add_library(${_t} INTERFACE IMPORTED)
        set_target_properties(${_t} PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${_smt_boost}")
    endif()
endforeach()

set(Boost_FOUND TRUE)
set(Boost_INCLUDE_DIRS "${_smt_boost}")
