(ns slipstream.ffi
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn last-error "ss_last_error" [] :string)
(ffi/defcfn atlas-new "ss_atlas_new" [:uint32] :pointer)
(ffi/defcfn atlas-destroy "ss_atlas_destroy" [:pointer] :int32)
(ffi/defcfn atlas-build "ss_atlas_build" [:pointer] :int32)
(ffi/defcfn atlas-built? "ss_atlas_is_built" [:pointer] :bool)
