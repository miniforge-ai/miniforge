;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-packs
  "Join requested pack constraints to unique, exact resolved artifacts."
  (:require [ai.miniforge.evidence-bundle.schema.version-constraint :as version]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} unique-by? [field records]
  (= (count records) (count (set (map field records)))))

(defn- ^{:stratum 0} bound-version? [resolved pack]
  (version/satisfied? (:pack/version pack) (get resolved (:pack/id pack))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} consistent? [bound packs]
  (let [resolved (zipmap (map :pack/id packs) (map :pack/version packs))]
    (and (unique-by? :pack/id bound) (unique-by? :pack/id packs)
         (every? (partial bound-version? resolved) bound))))
