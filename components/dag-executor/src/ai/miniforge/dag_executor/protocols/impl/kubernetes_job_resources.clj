;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-job-resources
  "Validate capsule budgets and project task resources into a Kubernetes Job."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-workdir "/workspace")

(def ^{:stratum 0 :private true} config-resource "config/dag-executor/runtime/kubernetes.edn")

(def ^{:stratum 0 :private true} capsule-budget-schema
  [:map [:resources/timeout-seconds pos-int?]])

;; Default resource limits (matches job-template.yaml)
(def ^{:stratum 0} default-resources
  {:limits {:memory "512Mi" :cpu "500m"}
   :requests {:memory "256Mi" :cpu "100m"}})

(defn ^{:stratum 0} build-env-vars
  "Build K8s env var specs from a map."
  [env-map]
  (mapv (fn [[k v]] {:name (name k) :value (str v)}) env-map))

(defn- ^{:stratum 0} invalid-deadline-message []
  (messages/t :kubernetes/invalid-deadline))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configure-deadline
  "Validate the packaged default and apply a validated executor override."
  [settings]
  (let [defaults (config/load-config-resource config-resource [:resources/timeout-seconds])
        effective (merge defaults settings)]
    (when-not (and (m/validate capsule-budget-schema defaults)
                   (m/validate capsule-budget-schema effective))
      (throw (ex-info (invalid-deadline-message)
                      {:config/key :resources/timeout-seconds
                       :config/resource config-resource})))
    effective))

(defn ^{:stratum 1} task-resources
  "Use the task's explicit budget if present, otherwise inherit the executor budget."
  [settings overrides]
  (let [effective (when (or (nil? overrides) (map? overrides))
                    (merge (select-keys settings [:resources/timeout-seconds]) overrides))]
    (if (m/validate capsule-budget-schema effective)
      (result/ok effective)
      (result/err :invalid-capsule-deadline (invalid-deadline-message)))))

(defn ^{:stratum 1} build-resource-spec
  "Build K8s resource limits/requests spec.
   Merges provided resources with defaults."
  [resources]
  (update-vals (select-keys (merge-with merge default-resources resources) [:limits :requests])
               (fn [{:keys [memory cpu]}]
                 (cond-> {}
                   memory (assoc :memory memory)
                   cpu (assoc :cpu (str cpu))))))
