;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-settings
  "Validate packaged Kubernetes defaults and bounded readiness settings."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} config-resource
  "Keep overridable polling budgets and legacy defaults in packaged data."
  "config/dag-executor/runtime/kubernetes.edn")

(def ^{:stratum 0 :private true} millis-schema
  [:int {:min 1
         :max Integer/MAX_VALUE}])

(def ^{:stratum 0 :private true} resource-schema
  [:map
   [:limits [:map [:memory :string] [:cpu :string]]]
   [:requests [:map [:memory :string] [:cpu :string]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1 :private true} executor-schema
  [:map
   [:kubernetes/pod-ready-timeout-ms millis-schema]
   [:kubernetes/pod-ready-poll-interval-ms millis-schema]
   [:kubernetes/executable :string]
   [:kubernetes/workdir :string]
   [:kubernetes/resources resource-schema]])

(defn ^{:stratum 1} defaults
  "Load packaged data through the shared fail-closed resource boundary."
  []
  (config/load-config-resource config-resource))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} configure
  "Refuse invalid packaged or effective settings before any provider request."
  [overrides]
  (let [packaged (defaults)
        effective (merge packaged overrides)]
    (when-let [invalid (or (m/explain executor-schema packaged)
                           (m/explain executor-schema effective))]
      (let [key (-> invalid :errors first :in first)]
        (throw (ex-info (messages/t :kubernetes/invalid-setting {:key key})
                        {:config/key key
                         :config/resource config-resource}))))
    effective))

(comment (configure {}))
