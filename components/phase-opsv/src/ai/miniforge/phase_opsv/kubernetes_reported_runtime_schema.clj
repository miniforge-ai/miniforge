;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reported-runtime-schema
  "Partial outside-Pod observations are not complete immutable runtime-image proofs."
  (:require [ai.miniforge.phase-opsv.kubernetes-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-pod-consistency :as consistency]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PodSpec (mu/optional-keys resource/PodSpec ["nodeName"]))

(def ^{:stratum 0} Statuses
  [:vector [:map ["name" schema/NonBlankString] ["imageID" {:optional true} :string]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Pod
  [:and
   [:map ["spec" PodSpec]
    ["status" {:optional true}
     (into [:map] (for [[_spec field _kind] images/container-groups] [field {:optional true} Statuses]))]]
   [:fn consistency/reported?]])
