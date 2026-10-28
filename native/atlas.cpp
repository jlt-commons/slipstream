#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

#include <memory>

const char* ss_last_error(void) noexcept {
  return slipstream::last_error;
}

void* ss_atlas_new(uint32_t width, int curve_precision) noexcept {
  ss_try(nullptr,
    if(width == 0) {
      throw std::invalid_argument("texture width must be greater than zero");
    }
    if((width & (width - 1)) != 0) {
      throw std::invalid_argument("texture width must be a power of two");
    }
    if(curve_precision != 16 && curve_precision != 32) {
      throw std::invalid_argument("curve precision must be 16 or 32");
    }
    auto atlas = std::make_unique<slughorn::Atlas>(width);
    if(curve_precision == 16) {
      atlas->setCurveTextureFormat(slughorn::Atlas::TextureData::Format::RGBA16F);
    }
    return atlas.release();
  );
}

int ss_atlas_destroy(void* atlas) noexcept {
  ss_try(-1,
    delete slipstream::require_atlas(atlas);
    return 0;
  );
}

int ss_atlas_build(void* atlas) noexcept {
  ss_try(-1,
    slipstream::require_atlas(atlas)->build();
    return 0;
  );
}

bool ss_atlas_is_built(void* atlas) noexcept {
  ss_try(false,
    return slipstream::require_atlas(atlas)->isBuilt();
  );
}
