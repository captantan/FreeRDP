# SPDX-License-Identifier: Apache-2.0
#
# captantan fork: job count for the dependencies that are built with make.
#
# A bare "make -j" starts as many jobs as make can find work for, and Ninja, which runs these
# ExternalProject steps, does not hand make a job limit. On a small build machine that can start
# hundreds of compilers at once. ExternalOpenSSL, ExternalOpenH264 and ExternalFFmpeg pass
# -j${CAPTANTAN_MAKE_JOBS} instead.

include_guard(GLOBAL)

include(ProcessorCount)
ProcessorCount(CAPTANTAN_MAKE_JOBS)
if(CAPTANTAN_MAKE_JOBS EQUAL 0)
  set(CAPTANTAN_MAKE_JOBS 1)
endif()
message(STATUS "captantan fork: make job count for external dependencies: ${CAPTANTAN_MAKE_JOBS}")
