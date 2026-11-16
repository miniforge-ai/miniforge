;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.native-probe
  "Bound the lifetime of trusted local read-only probes."
  (:require [babashka.process :as process]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} timeout-ms
  "Storage qualification must finish or refuse; never wait indefinitely for a helper."
  5000)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-output [arguments input]
  (let [child (process/process arguments {:in input :out :string :err :discard})]
    (try+
      (let [result (deref child timeout-ms nil)]
        (when (= 0 (:exit result)) (:out result)))
      (finally (process/destroy-tree child)))))
