#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

const char* ss_last_error(void) {
  return slipstream::last_error.c_str();
}

void* ss_atlas_new(uint32_t width) {
  ss_try(nullptr,
    if(width == 0) {
      throw std::invalid_argument("texture width must be greater than zero");
    }
    if((width & (width - 1)) != 0) {
      throw std::invalid_argument("texture width must be a power of two");
    }
    return new slughorn::Atlas(width);
  );
}

int ss_atlas_destroy(void* atlas) {
  ss_try(-1,
    delete slipstream::require_atlas(atlas);
    return 0;
  );
}

int ss_atlas_build(void* atlas) {
  ss_try(-1,
    slipstream::require_atlas(atlas)->build();
    return 0;
  );
}

bool ss_atlas_is_built(void* atlas) {
  ss_try(false,
    return slipstream::require_atlas(atlas)->isBuilt();
  );
}
