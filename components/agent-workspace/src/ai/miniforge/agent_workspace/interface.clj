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
(ns ai.miniforge.agent-workspace.interface
  "API surface class 1: one task's multi-repository workspace inside a leased
   environment. Every repository must be in the `:workspace/repos` set of a
   live root `:effect/pr-create` grant for the run; each gets one task
   branch, and its pull request is one effect transaction under a fixed key,
   so a retried delivery never opens a second PR."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-workspace.messages :as msg]
   [ai.miniforge.agent-workspace.schema :as schema]
   [ai.miniforge.agent-workspace.workspace :as workspace]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.tenancy.interface :as tenancy]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version "The revision every workspace request names." schema/contract-version)

(def ^{:stratum 0} Request "Which repositories to clone, and where." schema/Request)

(def ^{:stratum 0} Workspace "A cloned workspace and its lease." schema/Workspace)

(def ^{:stratum 0} default-git-timeout-ms
  "Fallback deadline for one git command: a clone of a mid-sized repository."
  300000)

(def ^{:stratum 0} ControllerOptions
  [:map
   [:environment map?]
   [:store-dir :string]
   [:repo-base-url [:re #"\Ahttps://[^\s]+/\z"]]
   [:open-pr! fn?]
   [:clock fn?]
   [:git-timeout-ms {:optional true} pos-int?]])

(defn- ^{:stratum 0} invalid [schema value]
  (anomaly/sub-anomaly :invalid-input :anomalies.agent-workspace/invalid-input
                       (msg/t :anomalies.agent-workspace/invalid-input) {:explain (m/explain schema value)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check [& pairs]
  (some (fn [[schema value]] (when-not (m/validate schema value) (invalid schema value))) (partition 2 pairs)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-controller
  "A workspace controller: `:environment` (an agent-environment controller),
   `:store-dir` for effect records, `:repo-base-url` (an `https://` prefix a
   repository id and `.git` complete), `:open-pr!` (proposal map →
   `{:pr/number :pr/url}` or an anomaly), and `:clock`."
  [opts]
  (or (check ControllerOptions opts)
      {:workspace/environment (:environment opts)
       :workspace/store-dir (:store-dir opts)
       :workspace/repo-base-url (:repo-base-url opts)
       :workspace/open-pr! (:open-pr! opts)
       :workspace/clock (:clock opts)
       :workspace/git-timeout-ms (get opts :git-timeout-ms default-git-timeout-ms)}))

(defn ^{:stratum 2} clone!
  "Clone every repository of `request` onto its task branch, or refuse
   before any command runs."
  [wc acting lease grant request]
  (or (check tenancy/ActingContext acting env/Lease lease grant/ExecutionGrant grant schema/Request request)
      (workspace/clone! wc acting lease grant request)))

(defn ^{:stratum 2} deliver!
  "One delivery result per checkout: `{:repo/id :delivery/state
   :delivery/effect-id :delivery/pull-request}`. A repeat returns what the
   first delivery recorded."
  [wc acting grant workspace]
  (or (check tenancy/ActingContext acting grant/ExecutionGrant grant schema/Workspace workspace)
      (workspace/deliver! wc acting grant workspace)))
