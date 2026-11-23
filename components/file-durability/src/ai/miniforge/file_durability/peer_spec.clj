;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.peer-spec
  "Trusted host configuration for a distinct installed runtime, never shell syntax."
  (:require [ai.miniforge.file-durability.peer-validation :as validation]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Peer
  [:map {:closed true}
   [:peer/executable [:fn validation/absolute-file?]]
   [:peer/classpath [:and :string [:fn validation/absolute-classpath?]]]])

(comment (m/validate Peer {}))
