;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-configuration-schema
  "Supported Kubernetes configuration payloads, not generic resource envelopes."
  (:require [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ConfigMap
  [:and resource/Resource
   [:map ["apiVersion" [:= "v1"]] ["kind" [:= "ConfigMap"]]
    ["metadata" [:map ["namespace" schema/NonBlankString]]]
    ["data" {:optional true} [:map-of :string :string]]
    ["binaryData" {:optional true} [:map-of :string :string]]
    ["immutable" {:optional true} :boolean]]])

(comment ::typed-config-map-observation)
