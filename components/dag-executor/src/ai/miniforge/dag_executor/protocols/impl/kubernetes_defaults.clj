;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-defaults
  "Read fixed legacy projection data separately from operator-overridable settings."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} resource-path
  "Keep legacy CLI, workdir and resource defaults in one packaged data asset."
  "config/dag-executor/runtime/kubernetes-defaults.edn")

(def ^{:stratum 0 :private true} defaults-schema
  [:map
   [:executable [:string {:min 1}]]
   [:workdir [:string {:min 1}]]
   [:resources [:map
                [:limits [:map [:memory :string] [:cpu :string]]]
                [:requests [:map [:memory :string] [:cpu :string]]]]]])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} data []
  (let [value (config/load-config-resource resource-path)]
    (when-not (m/validate defaults-schema value)
      (throw (ex-info (messages/t :kubernetes/invalid-defaults)
                      {:config/resource resource-path})))
    value))

(comment (data))
