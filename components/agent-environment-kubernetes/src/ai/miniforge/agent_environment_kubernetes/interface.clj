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
(ns ai.miniforge.agent-environment-kubernetes.interface
  "API surface class 1: a Kubernetes provider for the agent-environment
   contract. One namespace and one pod per environment, on the RuntimeClass
   each isolation level maps to; a level with no installed RuntimeClass
   fails preflight and never runs on the node default instead. kubectl runs
   only against the configured context."
  (:require
   [ai.miniforge.agent-environment-kubernetes.boundary.kubectl :as kubectl]
   [ai.miniforge.agent-environment-kubernetes.messages :as msg]
   [ai.miniforge.agent-environment-kubernetes.provider :as provider]
   [ai.miniforge.agent-environment-kubernetes.settings :as settings]
   [ai.miniforge.anomaly.interface :as anomaly]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Settings "Provider settings." settings/Settings)

(defn ^{:stratum 0} default-settings
  "The shipped defaults: a pinned image and the node-default runtime only.
   They name no context; add `:kubernetes/context` before use."
  []
  @settings/defaults)

(defn ^{:stratum 0} kubectl-run
  "The kubectl runner `create-provider` uses by default, for callers that
   wrap it."
  [settings args stdin]
  (kubectl/run settings args stdin))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-provider
  "A provider over `settings`, or an `invalid-settings` anomaly. `runner`
   (default: kubectl) is `(runner settings args stdin)` returning
   `{:kubectl/exit :kubectl/out :kubectl/err}` or an anomaly."
  ([settings] (create-provider settings kubectl/run))
  ([settings runner]
   (if (m/validate Settings settings)
     (provider/->KubernetesProvider settings runner)
     (anomaly/sub-anomaly :invalid-input :anomalies.agent-environment-kubernetes/invalid-settings
                          (msg/t :anomalies.agent-environment-kubernetes/invalid-settings)
                          {:explain (m/explain Settings settings)}))))
