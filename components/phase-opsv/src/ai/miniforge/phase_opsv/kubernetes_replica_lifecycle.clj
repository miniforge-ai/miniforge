;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-lifecycle
  "Lifecycle invariants for Pods whose native controller membership is already proven."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.kubernetes-controller-reference :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-metadata-projection :as metadata]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} controller? [reference] (true? (get reference "controller")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} profile [pod]
  (let [original (get pod "metadata")
        others (filterv (complement controller?) (get original "ownerReferences"))
        lifecycle (metadata/project (assoc original "ownerReferences" others))
        controller (dissoc (ownership/controller pod) "uid" "name")]
    {:kubernetes/controller-role controller
     :resource/ownership-and-lifecycle lifecycle}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [pod]
  (let [projected (profile pod)]
    (anomaly/let-ok [digest (artifact/content-digest projected)]
      {:replica/id (resource/uid pod)
       :kubernetes/pod-lifecycle projected
       :content/hash digest})))
