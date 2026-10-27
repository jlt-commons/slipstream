(ns slipstream.ffi
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn last-error "ss_last_error" [] :string)
(ffi/defcfn atlas-new "ss_atlas_new" [:uint32] :pointer)
(ffi/defcfn atlas-destroy "ss_atlas_destroy" [:pointer] :int32)
(ffi/defcfn atlas-build "ss_atlas_build" [:pointer] :int32)
(ffi/defcfn atlas-built? "ss_atlas_is_built" [:pointer] :bool)

(def key-layout
  (ffi/layout [:struct [[:type :uint32] [:codepoint :uint32] [:name :pointer]]]))
(def color-layout
  (ffi/layout [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]))
(def transform-layout
  (ffi/layout [:struct [[:x :float] [:y :float] [:z :float]]]))
(def layer-layout
  (ffi/layout [:struct
               [[:key [:struct [[:type :uint32] [:codepoint :uint32] [:name :pointer]]]]
                [:color [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]]
                [:transform [:struct [[:x :float] [:y :float] [:z :float]]]]
                [:scale :float]
                [:effect-id :uint32]
                [:effect-param :float]
                [:gradient-id :uint32]
                [:pattern-id :uint32]
                [:bleed :float]
                [:draw-mode :uint8]
                [:blend-mode :uint8]]]))
(def shape-layout
  (ffi/layout [:struct
               [[:band-tex-x :uint32] [:band-tex-y :uint32]
                [:band-max-x :uint32] [:band-max-y :uint32]
                [:band-scale-x :float] [:band-scale-y :float]
                [:band-offset-x :float] [:band-offset-y :float]
                [:bearing-x :float] [:bearing-y :float]
                [:width :float] [:height :float] [:advance :float]
                [:origin-x :float] [:origin-y :float]
                [:origin-type :uint32]
                [:origin-data-x :float] [:origin-data-y :float]]]))
(def quad-layout
  (ffi/layout [:struct [[:x0 :float] [:y0 :float] [:x1 :float] [:y1 :float]]]))
(def texture-layout
  (ffi/layout [:struct
               [[:bytes :pointer] [:byte-count :uint64]
                [:width :uint32] [:height :uint32] [:depth :uint32]
                [:format :uint32]]]))
(def mesh-layout
  (ffi/layout [:struct
               [[:vertices :pointer] [:indices :pointer]
                [:vertex-count :uint64] [:index-count :uint64]]]))

(ffi/defcfn atlas-add-shape "ss_atlas_add_shape"
  [:pointer :pointer :pointer :uint64 :bool
   :float :float :float :float :float :int32 :int32
   :pointer :uint64 :pointer :uint64 :uint32 :float :float] :int32)
(ffi/defcfn atlas-shape "ss_atlas_get_shape" [:pointer :pointer :pointer] :int32)
(ffi/defcfn atlas-add-composite "ss_atlas_add_composite"
  [:pointer :pointer :pointer :uint64 :float] :int32)
(ffi/defcfn atlas-composite "ss_atlas_get_composite" [:pointer :pointer :pointer] :int32)
(ffi/defcfn atlas-composite-layers "ss_atlas_get_composite_layers"
  [:pointer :pointer :pointer :uint64 :pointer] :int32)
(ffi/defcfn atlas-texture "ss_atlas_texture" [:pointer :uint32 :pointer] :int32)
(ffi/defcfn shape-quad "ss_shape_quad" [:pointer :pointer :float :pointer] :int32)
(ffi/defcfn mesh-new "ss_mesh_new" [:pointer :pointer :uint64] :pointer)
(ffi/defcfn mesh-descriptor "ss_mesh_descriptor" [:pointer :pointer] :int32)
(ffi/defcfn mesh-destroy "ss_mesh_destroy" [:pointer] :int32)
(ffi/defcfn atlas-write "ss_atlas_write" [:pointer :string] :int32)
(ffi/defcfn atlas-read "ss_atlas_read" [:string] :pointer)
