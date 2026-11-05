;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-configuration-fixtures :as controllers]
            [ai.miniforge.phase-opsv.kubernetes-outside-fixtures :as outside]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sha256-hex-width
  "A SHA-256 runtime digest has 256 bits, encoded as 64 hexadecimal characters."
  64)

(defn ^{:stratum 0} input []
  (let [controlled (controllers/input)]
    {:capture (assoc (outside/input) :kubernetes/inventory (:kubernetes/inventory controlled))
     :bounds (:comparison/bounds controlled)}))

(defn ^{:stratum 0} with-member-field [capture path value]
  (reduce #(assoc-in %1 (into [:kubernetes/inventory :kubernetes/pod-inventory "items" %2] path) value)
          capture [0 1]))

(defn ^{:stratum 0} with-pool [capture value]
  (assoc-in capture [:kubernetes/nodes "items" 1 "metadata" "labels" kube/pool-label] value))

(defn ^{:stratum 0} environment-setting []
  [{"name" "SETTING" "value" "other"}])

(defn ^{:stratum 0} bounds [minimum maximum]
  {:replicas/min-count minimum :replicas/max-count maximum})

(defn ^{:stratum 0} with-lifecycle-metadata [capture]
  (reduce #(controllers/with-orderable-metadata %1 [:kubernetes/inventory :kubernetes/pod-inventory "items" %2 "metadata"])
          capture [0 1]))

(defn ^{:stratum 0} reverse-lifecycle-metadata [capture]
  (reduce #(controllers/reverse-metadata %1 [:kubernetes/inventory :kubernetes/pod-inventory "items" %2 "metadata"])
          capture [0 1]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} other-image []
  (str "docker-pullable://registry.invalid/service@sha256:" (apply str (repeat sha256-hex-width "b"))))
