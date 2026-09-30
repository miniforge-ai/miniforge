;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-finalization-publication
  "Atomically publish against the validated assembly version; retry concurrent accumulation."
  (:require [ai.miniforge.content-hash.interface :as hash]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finalize-current [expected bundle current]
  (if (= expected current)
    (assoc current :opsv.assembly/status :finalized :opsv.assembly/bundle bundle)
    current))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish! [store record candidate]
  (let [bundle-id (:evidence-bundle/id record)
        bundle (assoc candidate :evidence/content-hash (hash/content-hash candidate))
        transition (partial finalize-current record bundle)
        [old-state new-state] (swap-vals! store update bundle-id transition)]
    (if (= record (get old-state bundle-id))
      (get-in new-state [bundle-id :opsv.assembly/bundle])
      ::retry)))

(comment
  ::retry)
