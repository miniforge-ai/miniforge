;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-settings
  "Validate the Kubernetes executor's execution and transport budgets."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-defaults :as defaults]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-settings-spec :as spec]
            [ai.miniforge.dag-executor.result :as result]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} config-resource
  "Package capsule and create-request budgets separately from fixed projection data."
  "config/dag-executor/runtime/kubernetes.edn")

(defn ^{:stratum 0} task-resources
  "Use the task's explicit budget if present, otherwise inherit the executor budget."
  [settings overrides]
  (let [effective (when (or (nil? overrides) (map? overrides))
                    (merge (select-keys settings [:resources/timeout-seconds]) overrides))]
    (if (m/validate spec/CapsuleBudget effective)
      (result/ok effective)
      (result/err :invalid-capsule-deadline (messages/t :kubernetes/invalid-deadline)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configure
  "Validate packaged defaults and then effective executor settings before provider I/O."
  [overrides]
  (let [packaged (merge (defaults/data) (config/load-config-resource config-resource))
        effective (when (or (nil? overrides) (map? overrides)) (merge packaged overrides))
        invalid (or (m/explain spec/ExecutorSettings packaged)
                    (m/explain spec/ExecutorSettings effective))]
    (if invalid
      (let [key (-> invalid :errors first :in first)]
        (result/err :invalid-kubernetes-settings
                    (messages/t :kubernetes/invalid-setting {:key key})
                    {:config/key key
                     :config/resource config-resource}))
      (result/ok effective))))

(comment (configure {}))
