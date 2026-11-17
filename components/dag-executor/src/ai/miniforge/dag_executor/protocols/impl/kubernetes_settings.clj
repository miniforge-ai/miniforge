;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-settings
  "Validate the Kubernetes executor's execution and transport budgets."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} config-resource "config/dag-executor/runtime/kubernetes.edn")

(def ^{:stratum 0 :private true} capsule-budget-schema
  [:map [:resources/timeout-seconds pos-int?]])

(def ^{:stratum 0 :private true} millis-schema
  [:int {:min 1 :max Integer/MAX_VALUE}])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1 :private true} executor-schema
  (conj capsule-budget-schema
        [:kubernetes/creation-request-timeout-ms millis-schema]
        [:kubernetes/pod-ready-timeout-ms millis-schema]
        [:kubernetes/pod-ready-poll-interval-ms millis-schema]))

(defn ^{:stratum 1} task-resources
  "Use the task's explicit budget if present, otherwise inherit the executor budget."
  [settings overrides]
  (let [effective (when (or (nil? overrides) (map? overrides))
                    (merge (select-keys settings [:resources/timeout-seconds]) overrides))]
    (if (m/validate capsule-budget-schema effective)
      (result/ok effective)
      (result/err :invalid-capsule-deadline (messages/t :kubernetes/invalid-deadline)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} configure
  "Validate packaged defaults and then effective executor settings before provider I/O."
  [overrides]
  (let [defaults (config/load-config-resource config-resource)
        effective (merge defaults overrides)]
    (when-let [invalid (or (m/explain executor-schema defaults)
                           (m/explain executor-schema effective))]
      (let [key (-> invalid :errors first :in first)]
        (throw (ex-info (messages/t :kubernetes/invalid-setting {:key key})
                        {:config/key key :config/resource config-resource}))))
    effective))
