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
(ns ai.miniforge.phase-opsv.load-events
  "Construct N3 events for guarded OPSV load steps."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} load-step-event
  [stream workflow-id evidence-id step]
  (event-stream/load-step
   stream workflow-id evidence-id
   {:opsv/step-id (:step/id step)
    :opsv/intended-load (:step/intended-load step)
    :opsv/observed-load (:step/observed-load step)}))

(defn- ^{:stratum 0} guardrail-abort-event
  [stream workflow-id evidence-id step]
  (let [abort-data (:step/guardrail-abort step)]
    (event-stream/guardrail-abort
     stream workflow-id evidence-id
     {:opsv/trigger (get abort-data :trigger :guardrail)
      :opsv/threshold (get abort-data :threshold {})
      :opsv/observed (get abort-data :observed (:step/observations step))
      :opsv/rollback-action (get abort-data :rollback-action
                                 :restore-previous-policy)})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} events-for-step
  [stream workflow-id evidence-id step]
  (let [load-event (load-step-event stream workflow-id evidence-id step)
        abort? (true? (get-in step [:step/metrics :guardrail-abort?]))]
    (cond-> [load-event]
      abort? (conj (guardrail-abort-event stream workflow-id evidence-id
                                          step)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project
  [stream workflow-id evidence-id steps]
  (mapcat (partial events-for-step stream workflow-id evidence-id) steps))
