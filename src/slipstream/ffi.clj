(ns slipstream.ffi
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn last-error "ss_last_error" [] :string)
(ffi/defcfn atlas-new "ss_atlas_new" [:uint32 :int32] :pointer)
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
  [:pointer :pointer :pointer :uint64 :float :pointer] :int32)
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

(def path-sample-layout
  (ffi/layout [:struct [[:x :float] [:y :float] [:angle :float] [:arc-length :float]]]))
(def gradient-stop-layout
  (ffi/layout [:struct
               [[:t :float]
                [:color [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]]]]))
(def mask-layout
  (ffi/layout [:struct
               [[:key [:struct [[:type :uint32] [:codepoint :uint32] [:name :pointer]]]]
                [:params [:array :float 6]]
                [:type :uint8] [:invert :uint8] [:has-key :uint8]]]))
(def font-metrics-layout
  (ffi/layout [:struct
               [[:units-per-em :float] [:cap-height-ratio :float]
                [:x-height-ratio :float] [:ascender-ratio :float]
                [:descender-ratio :float]]]))
(def font-load-layout
  (ffi/layout [:struct
               [[:loaded :uint64]
                [:metrics [:struct [[:units-per-em :float] [:cap-height-ratio :float]
                                    [:x-height-ratio :float] [:ascender-ratio :float]
                                    [:descender-ratio :float]]]]
                [:family :pointer] [:style :pointer]]]))

(ffi/defcfn font-load "ss_font_load"
  [:pointer :string :pointer :uint64 :uint32 :bool :uint8 :pointer] :int32)
(ffi/defcfn text-layout "ss_text_layout"
  [:pointer :pointer :uint64 :float :float :float :pointer :pointer
   :uint32 :uint32 :uint8 :pointer :uint64 :pointer] :int32)
(ffi/defcfn path-new "ss_path_new" [] :pointer)
(ffi/defcfn path-destroy "ss_path_destroy" [:pointer] :int32)
(ffi/defcfn path-command "ss_path_command" [:pointer :uint32 :pointer :uint64] :int32)
(ffi/defcfn path-add "ss_path_add" [:pointer :pointer :pointer] :int32)
(ffi/defcfn path-sample "ss_path_sample" [:pointer :float :pointer] :int32)
(ffi/defcfn path-arc-length "ss_path_arc_length" [:pointer :pointer] :int32)
(ffi/defcfn path-stroke "ss_path_stroke"
  [:pointer :float :bool :uint32 :uint32 :float] :int32)
(ffi/defcfn atlas-add-gradient "ss_atlas_add_gradient"
  [:pointer :uint32 :pointer :uint64 :pointer :uint64] :int64)
(ffi/defcfn atlas-add-pattern "ss_atlas_add_pattern"
  [:pointer :pointer :pointer :pointer :uint32 :uint32] :int64)
(ffi/defcfn atlas-composite-mask "ss_atlas_get_composite_mask"
  [:pointer :pointer :pointer] :int32)
(ffi/defcfn canvas-new "ss_canvas_new" [:pointer :uint32] :pointer)
(ffi/defcfn canvas-destroy "ss_canvas_destroy" [:pointer] :int32)
(ffi/defcfn canvas-command "ss_canvas_command" [:pointer :uint32 :pointer :uint64] :int32)
(ffi/defcfn canvas-add-path "ss_canvas_add_path" [:pointer :pointer :pointer] :int32)
(ffi/defcfn canvas-set-splits "ss_canvas_set_splits"
  [:pointer :pointer :uint64 :pointer :uint64] :int32)
(ffi/defcfn canvas-fill "ss_canvas_fill"
  [:pointer :pointer :pointer
   [:by-value [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]]
   :float :uint32 :float :float :uint32 :uint32 :pointer] :int32)
(ffi/defcfn canvas-stroke "ss_canvas_stroke"
  [:pointer :pointer :pointer :float
   [:by-value [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]]
   :float :uint32 :float :float :uint32 :uint32 :float :uint32 :uint32 :pointer] :int32)
(ffi/defcfn canvas-define-shape "ss_canvas_define_shape"
  [:pointer :pointer :pointer :float :uint32 :float :float] :int32)
(ffi/defcfn canvas-text "ss_canvas_text"
  [:pointer :string :float :float :float
   [:by-value [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]]
   :pointer :uint32 :uint32 :uint8] :int32)
(ffi/defcfn canvas-finalize "ss_canvas_finalize" [:pointer :pointer] :int32)
(ffi/defcfn canvas-set-mask "ss_canvas_set_mask" [:pointer :pointer] :int32)
(ffi/defcfn canvas-mask-path "ss_canvas_mask_path"
  [:pointer :float :bool :pointer] :int32)

(def layers-view-layout
  (ffi/layout [:struct [[:data :pointer] [:count :uint64]]]))

(def gl-context-options-layout
  (ffi/layout [:struct [[:title :pointer] [:width :int32] [:height :int32]
                        [:bounds [:array :float 4]]
                        [:has-bounds :uint8] [:visible :uint8]]]))
(def gl-draw-command-layout
  (ffi/layout [:struct [[:handle :pointer]
                        [:transform [:struct [[:x :float] [:y :float] [:z :float]]]]
                        [:color [:struct [[:r :float] [:g :float] [:b :float] [:a :float]]]]
                        [:scale :float] [:visible :uint8]]]))
(ffi/defcfn gl-context-create "ss_gl_context_create" [:pointer] :pointer)
(ffi/defcfn gl-context-destroy "ss_gl_context_destroy" [:pointer] :int32)
(ffi/defcfn gl-poll "ss_gl_poll" [:pointer] :int32)
(ffi/defcfn gl-should-close "ss_gl_should_close" [:pointer] :bool)
(ffi/defcfn gl-upload "ss_gl_upload" [:pointer :pointer :pointer] :pointer)
(ffi/defcfn gl-release "ss_gl_release" [:pointer :pointer] :int32)
(ffi/defcfn gl-render "ss_gl_render" [:pointer :pointer :uint64] :int32)
(ffi/defcfn gl-swap "ss_gl_swap" [:pointer] :int32)

(ffi/defcfn glfw-run "ss_glfw_run"
  [:pointer
   [:by-value [:struct [[:data :pointer] [:count :uint64]]]]
   :pointer :int32 :pointer :int32]
  :int32 :blocking)
(ffi/defcfn read-stdin-text "ss_read_stdin_text" [] :pointer :blocking)
(ffi/defcfn free-stdin-text "ss_free_stdin_text" [:pointer] :void)
