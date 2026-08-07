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
(ns ai.miniforge.control-plane.orchestrator-test-support
  "Mock adapter and orchestrator opts shared by the orchestrator test namespaces.

   Three namespaces drive the same orchestrator through the same fake
   adapter and the same minimal opts map. They live here so a defect in the
   fake is fixed once rather than once per copy — the same reason the async
   wait helpers moved to `async-test-support`."
  (:require
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane-adapter.protocol :as adapter]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} test-workflow-id
  "The workflow id `base-orchestrator-opts` puts on every orchestrator it
   builds, in place of the random one `create-orchestrator` would generate."
  (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001"))

(defn ^{:stratum 0} make-mock-adapter
  "Create a mock adapter implementing ControlPlaneAdapter.
   `overrides` is a map of {:discover-agents fn, :poll-agent-status fn,
                             :deliver-decision fn, :send-command fn, :adapter-id kw}.

   Every protocol method not named in `overrides` answers with the benign
   default below, so a test states only the behaviour it is about."
  [overrides]
  (let [id (get overrides :adapter-id :test-adapter)]
    (reify adapter/ControlPlaneAdapter
      (adapter-id [_] id)
      (discover-agents [_ config]
        (if-let [f (:discover-agents overrides)]
          (f config)
          []))
      (poll-agent-status [_ agent-record]
        (if-let [f (:poll-agent-status overrides)]
          (f agent-record)
          nil))
      (deliver-decision [_ agent-record decision]
        (if-let [f (:deliver-decision overrides)]
          (f agent-record decision)
          {:delivered? true}))
      (send-command [_ agent-record command]
        (if-let [f (:send-command overrides)]
          (f agent-record command)
          {:success? true})))))

(defn ^{:stratum 0} base-opts
  "Minimal valid opts for creating an orchestrator, with `overrides` merged last.

   The 50ms intervals only bound how soon a *redundant* second discovery or
   poll pass runs — `start!` runs the first pass of each before its loop
   sleeps — so they keep a background loop from idling through a test
   without making any assertion depend on the scheduler."
  [& [overrides]]
  (merge {:adapters []
          :discovery-interval-ms 50
          :poll-interval-ms 50}
         overrides))

(defn ^{:stratum 0} register-running-agent!
  "Helper: register an agent and transition to :running.

   Returns the record as the registry holds it after the transition, not
   the one `register-agent!` returned, so a caller reads the post-transition
   status rather than a stale `:initializing`."
  [reg agent-info]
  (let [rec (registry/register-agent! reg agent-info)]
    (registry/transition-agent! reg (:agent/id rec) :running)
    (registry/get-agent reg (:agent/id rec))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} base-orchestrator-opts
  "`base-opts` with `:workflow-id` pinned. `overrides` still wins over both.

   `create-orchestrator` generates a random workflow-id when opts omit one,
   so the difference from bare `base-opts` is a stable id on every published
   event rather than a fresh one per orchestrator."
  [& [overrides]]
  (base-opts (merge {:workflow-id test-workflow-id} overrides)))
