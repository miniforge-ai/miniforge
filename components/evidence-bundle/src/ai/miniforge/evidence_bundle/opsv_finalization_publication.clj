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

(defn ^{:stratum 0} sealed-bundle [candidate sealed-at]
  (let [dated (assoc candidate :evidence/sealed-at sealed-at :compliance/created-at sealed-at)]
    (assoc dated :evidence/content-hash (hash/content-hash dated))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-sealed! [store record bundle]
  (let [bundle-id (:evidence-bundle/id record)
        transition (partial finalize-current record bundle)
        [old-state new-state] (swap-vals! store update bundle-id transition)]
    (if (= record (get old-state bundle-id))
      (get-in new-state [bundle-id :opsv.assembly/bundle])
      ::retry)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [store record candidate]
  (publish-sealed! store record (sealed-bundle candidate (java.time.Instant/now))))

(comment
  ::retry)
