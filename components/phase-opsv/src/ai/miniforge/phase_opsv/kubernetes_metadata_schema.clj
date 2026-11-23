;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-metadata-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-schema :as controller]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unique-values? [values]
  (= (count values) (count (set values))))

(defn- ^{:stratum 0} unique-owner-uids? [references]
  (= (count references) (count (set (map #(get % "uid") references)))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Metadata
  [:map
   ["finalizers" {:optional true} [:and [:vector schema/NonBlankString] [:fn unique-values?]]]
   ["ownerReferences" {:optional true}
    [:and [:vector controller/OwnerReference] [:fn unique-owner-uids?]]]])
