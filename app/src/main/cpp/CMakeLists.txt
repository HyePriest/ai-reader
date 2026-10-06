cmake_minimum_required(VERSION 3.22.1)
project(ebook_import C)
set(CMAKE_C_STANDARD 99)
set(MOBI_SRC libmobi/src)
# Keep libmobi separately replaceable under LGPL; DRM decryption is disabled.
add_library(mobi SHARED
    ${MOBI_SRC}/buffer.c ${MOBI_SRC}/compression.c ${MOBI_SRC}/debug.c
    ${MOBI_SRC}/index.c ${MOBI_SRC}/memory.c ${MOBI_SRC}/meta.c
    ${MOBI_SRC}/parse_rawml.c ${MOBI_SRC}/read.c ${MOBI_SRC}/structure.c
    ${MOBI_SRC}/util.c ${MOBI_SRC}/write.c ${MOBI_SRC}/xmlwriter.c ${MOBI_SRC}/opf.c)
target_compile_definitions(mobi PRIVATE USE_XMLWRITER HAVE_UNISTD_H HAVE_STRDUP MOBI_INLINE=inline PACKAGE_VERSION="0.12")
target_link_libraries(mobi z)
add_library(ebook_import SHARED mobi_bridge.c)
target_include_directories(ebook_import PRIVATE ${MOBI_SRC})
target_link_libraries(ebook_import mobi)
