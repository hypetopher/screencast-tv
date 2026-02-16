cmake_minimum_required(VERSION 3.22.1)
project(airplay_crypto)

add_library(
    airplay_crypto
    SHARED
    airplay_crypto_jni.cpp
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/sap_hash.c
    playfair/modified_md5.c
    playfair/hand_garble.c
)

find_library(log-lib log)

target_link_libraries(
    airplay_crypto
    ${log-lib}
)
