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
(ns ai.miniforge.agent-worker.fake
  "An in-memory worker for contract tests. It writes its input into the
   environment, so artifacts and stale leases go through the real
   environment lifecycle. Options: `:fake/capabilities`,
   `:fake/preflight-failures`, `:fake/final-state`, `:fake/usage`."
  (:require
   [ai.miniforge.agent-worker.fake.turns :as turns]
   [ai.miniforge.agent-worker.interface.protocol :as protocol]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-capabilities
  {:capabilities/worker :worker/fake
   :capabilities/steering :steering/between-turns
   :capabilities/cancellation :cancellation/process-signal
   :capabilities/budget :budget/observed
   :capabilities/usage :usage/at-exit})

(def ^{:stratum 0} final-exits
  "Exit code a finished fake turn reports for each final state."
  {:state/completed 0 :state/failed 1})

(defn ^{:stratum 0} starts
  "How many turns the fake has started."
  [worker]
  (get @(:state worker) :fake/starts 0))

;------------------------------------------------------------------------------ Layer 1

(defrecord ^{:stratum 1} FakeWorker [state opts]
  protocol/AgentWorker
  (capabilities [_] (get opts :fake/capabilities default-capabilities))
  (preflight [_ _handle] (get opts :fake/preflight-failures []))
  (start! [_ handle submission]
    (turns/turn! state submission 1 (:submission/prompt submission) handle))
  (inspect [_ _handle session]
    (let [final (get opts :fake/final-state :state/completed)
          cancelled? (get-in @state [(:session/process-ref session) :cancelled?])]
      (if cancelled?
        {:status/state :state/cancelled :status/exit nil}
        {:status/state final :status/exit (get final-exits final)})))
  (send-input! [_ handle submission session text]
    (turns/turn! state submission (inc (:session/turn session)) text handle))
  (request-cancel! [this _handle session]
    (let [requested? (not= :cancellation/none (:capabilities/cancellation (protocol/capabilities this)))]
      (when requested? (swap! state assoc-in [(:session/process-ref session) :cancelled?] true))
      {:cancel/requested? requested?}))
  (artifact-paths [_ _handle session]
    [(get-in @state [(:session/process-ref session) :path])])
  (usage [_ _handle _session]
    (get opts :fake/usage {})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create [opts] (->FakeWorker (atom {}) opts))
