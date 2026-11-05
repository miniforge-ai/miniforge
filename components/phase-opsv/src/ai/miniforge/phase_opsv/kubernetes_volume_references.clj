;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-references
  (:require [ai.miniforge.phase-opsv.kubernetes-images :as images]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} container-fields
  "The native regular, init and ephemeral container groups share volume-reference semantics."
  (mapv first images/container-groups))

(def ^{:stratum 0} reference-fields
  "Both filesystem mounts and block-device references must resolve without alias collapse."
  ["volumeMounts" "volumeDevices"])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} container-references [container]
  (mapcat #(get container %) reference-fields))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} references [spec]
  (mapcat container-references (mapcat #(get spec %) container-fields)))

(comment ::shared-native-reference-vocabulary-for-admission-and-projection)
