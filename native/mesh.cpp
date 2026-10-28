#include "slipstream_shim.h"
#include "slipstream_internal.hpp"

#include <memory>
#include <vector>

namespace {

struct OwnedMesh {
  std::vector<float> vertices;
  std::vector<uint16_t> indices;
};

slughorn::Key mesh_key(const SSKey* key) {
  if(!key) throw std::invalid_argument("key is null");
  if(key->type == 0) return slughorn::Key(key->codepoint);
  if(key->type == 1 && key->name) return slughorn::Key(key->name);
  throw std::invalid_argument("invalid layer key");
}

OwnedMesh* require_mesh(const void* mesh) {
  if(!mesh) throw std::invalid_argument("mesh handle is null");
  return static_cast<OwnedMesh*>(const_cast<void*>(mesh));
}

}

void* ss_mesh_new(void* atlas, const SSLayer* layers, uint64_t layer_count) noexcept {
  ss_try(nullptr,
    if(layer_count > 0 && !layers) throw std::invalid_argument("layers are null");
    const auto* source = slipstream::require_atlas(atlas);
    auto mesh = std::make_unique<OwnedMesh>();
    size_t base = 0;

    for(uint64_t layer_index = 0; layer_index < layer_count; ++layer_index) {
      const SSLayer& layer = layers[layer_index];
      const auto shape = source->getShape(mesh_key(&layer.key));
      if(!shape) continue;
      if(base + 4 > 65536) {
        throw std::invalid_argument(
          "mesh exceeds the 65536-vertex unsigned-short index limit at layer " +
          std::to_string(layer_index) + "; split the layers into smaller meshes");
      }

      const slughorn::Transform transform = {
        layer.transform.x, layer.transform.y, layer.transform.z
      };
      const slughorn::Quad q = shape->computeQuad(transform, layer.scale);
      constexpr float expansion = 0.01f;
      const float e = expansion * layer.scale;
      const float x0 = q.x0 - e, y0 = q.y0 - e;
      const float x1 = q.x1 + e, y1 = q.y1 + e;
      const float em_x0 = shape->bearingX - expansion;
      const float em_y0 = shape->bearingY - shape->height - expansion;
      const float em_x1 = shape->bearingX + shape->width + expansion;
      const float em_y1 = shape->bearingY + expansion;
      const float positions[4][3] = {
        {x0, y0, 0.0f}, {x1, y0, 0.0f}, {x1, y1, 0.0f}, {x0, y1, 0.0f}
      };
      const float em_coords[4][4] = {
        {em_x0, em_y0, 0.0f, 0.0f}, {em_x1, em_y0, 1.0f, 0.0f},
        {em_x1, em_y1, 1.0f, 1.0f}, {em_x0, em_y1, 0.0f, 1.0f}
      };

      for(int corner = 0; corner < 4; ++corner) {
        mesh->vertices.insert(mesh->vertices.end(), {
          positions[corner][0], positions[corner][1], positions[corner][2],
          layer.color.r, layer.color.g, layer.color.b, layer.color.a,
          em_coords[corner][0], em_coords[corner][1],
          em_coords[corner][2], em_coords[corner][3],
          shape->bandScaleX, shape->bandScaleY,
          shape->bandOffsetX, shape->bandOffsetY,
          static_cast<float>(shape->bandTexX), static_cast<float>(shape->bandTexY),
          static_cast<float>(shape->bandMaxX), static_cast<float>(shape->bandMaxY),
          static_cast<float>(layer.effect_id)
        });
      }

      mesh->indices.insert(mesh->indices.end(), {
        static_cast<uint16_t>(base + 0), static_cast<uint16_t>(base + 1),
        static_cast<uint16_t>(base + 2), static_cast<uint16_t>(base + 0),
        static_cast<uint16_t>(base + 2), static_cast<uint16_t>(base + 3)
      });
      base += 4;
    }
    return mesh.release();
  );
}

int ss_mesh_descriptor(const void* mesh, SSMeshDescriptor* out) noexcept {
  ss_try(-1,
    if(!out) throw std::invalid_argument("mesh descriptor output is null");
    const auto* source = require_mesh(mesh);
    *out = {
      source->vertices.data(), source->indices.data(),
      source->vertices.size() / 20, source->indices.size()
    };
    return 0;
  );
}

int ss_mesh_destroy(void* mesh) noexcept {
  ss_try(-1,
    delete require_mesh(mesh);
    return 0;
  );
}
