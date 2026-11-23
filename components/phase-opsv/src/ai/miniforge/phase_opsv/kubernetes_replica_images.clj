;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-images
  (:require [ai.miniforge.phase-opsv.kubernetes-images :as images]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private immutable-digest #"(sha256:[0-9a-f]{64})\z")

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} entry [observed]
  [(str (name (:container/kind observed)) "/" (:container/name observed))
   (second (re-find immutable-digest (:image/id observed)))])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} digests [pod]
  (into {} (map entry) (images/pod-images pod)))
