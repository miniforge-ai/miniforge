;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.legacy-control
  "Prevent legacy workflow barriers from claiming to control current publication."
  (:require [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.current-publication :as current]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} invoke! [operation stream opts]
  (if (current/active? stream)
    (model/failure :unsupported :publication/unsupported-control nil)
    (operation stream opts)))

(comment
  ::legacy-barrier-dispatch)
