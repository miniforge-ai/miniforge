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
(ns ai.miniforge.agent-worker.schema
  "Versioned values of the agent-worker contract: the binding every call
   carries, what was selected, the native session reference, and usage in
   which an unknown quantity is nil, never zero."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The revision every binding names; any other is refused."
  "agent-worker/v1")

(def ^{:stratum 0} states
  "What inspection can report. `:state/unknown` means the worker could not
   tell, never that the session finished."
  [:state/running :state/completed :state/failed :state/cancelled :state/unknown])

(def ^{:stratum 0} usage-statuses
  "`:usage/unknown` carries no numbers; `:usage/partial` some; never zeros
   standing in for a missing report."
  [:usage/reported :usage/partial :usage/unknown])

(def ^{:stratum 0} NonBlank [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} Sha256Hex [:re #"\A[0-9a-f]{64}\z"])

(def ^{:stratum 0} Capabilities
  "What a worker declares. Steering between turns resumes the native
   session with new input; a cancel is a request whose effect inspection
   confirms."
  [:map {:closed true}
   [:capabilities/worker :keyword]
   [:capabilities/steering [:enum :steering/between-turns :steering/none]]
   [:capabilities/cancellation [:enum :cancellation/process-signal :cancellation/none]]
   [:capabilities/budget [:enum :budget/hard-ceiling :budget/observed :budget/none]]
   [:capabilities/usage [:enum :usage/at-exit :usage/unavailable]]])

(def ^{:stratum 0} usage-quantities [:usage/input-tokens :usage/output-tokens :usage/cost-usd])

(def ^{:stratum 0} Cost
  "A finite, non-negative amount of US dollars. NaN fails both bounds."
  [:and number? [:>= 0] [:< ##Inf]])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} status-matches-quantities?
  "Unknown has no quantity, reported has all three, partial some but not all."
  [usage]
  (let [present (count (keep usage usage-quantities))]
    (case (:usage/status usage)
      :usage/unknown (zero? present)
      :usage/reported (= (count usage-quantities) present)
      :usage/partial (< 0 present (count usage-quantities)))))

(def ^{:stratum 1} Status
  [:map {:closed true}
   [:status/state (into [:enum] states)]
   [:status/exit [:maybe int?]]])

(def ^{:stratum 1} Binding
  "Who and what a worker call belongs to: the logical run and task, the
   environment and lease generation it runs in, the spend grant, the digest
   of the selected config, and the attempt. Native session ids are worker
   references, never run identities."
  [:map {:closed true}
   [:worker/contract-version [:= contract-version]]
   [:worker/tenant-id :uuid]
   [:worker/principal-id :uuid]
   [:worker/run-id :uuid]
   [:worker/task-id :uuid]
   [:worker/environment-uid NonBlank]
   [:worker/lease-generation pos-int?]
   [:worker/grant-id :uuid]
   [:worker/config-digest Sha256Hex]
   [:worker/attempt pos-int?]])

(def ^{:stratum 1} Config
  "What was selected. `:config/worker` names exactly one worker; an
   unavailable one fails rather than being replaced."
  [:map {:closed true}
   [:config/worker :keyword]
   [:config/workdir NonBlank]
   [:config/model {:optional true} NonBlank]
   [:config/max-budget-usd {:optional true} [:and Cost [:> 0]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Usage
  [:and
   [:map {:closed true}
    [:usage/status (into [:enum] usage-statuses)]
    [:usage/input-tokens [:maybe nat-int?]]
    [:usage/output-tokens [:maybe nat-int?]]
    [:usage/cost-usd [:maybe Cost]]]
   [:fn status-matches-quantities?]])

(def ^{:stratum 2} Session
  "A started attempt: its binding and the worker's native references. A
   steered session keeps the native id and gets a new process and turn."
  [:map {:closed true}
   [:session/binding Binding]
   [:session/worker :keyword]
   [:session/native-id [:maybe NonBlank]]
   [:session/process-ref NonBlank]
   [:session/turn pos-int?]])

(def ^{:stratum 2} Submission
  [:map {:closed true}
   [:submission/binding Binding]
   [:submission/config Config]
   [:submission/prompt NonBlank]])
