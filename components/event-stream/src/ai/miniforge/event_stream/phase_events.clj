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
(ns ai.miniforge.event-stream.phase-events
  "Phase-completion event construction.

   Split from `compound-events` because the phase cluster is three
   strata deep on its own — request accessor, redirect projection,
   then the constructor — and sharing a namespace with the chain and
   dependency constructors pushed the file over the three-layer
   budget (SL003)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.current-publication :as current]
   [ai.miniforge.event-stream.messages :as msg]
   [ai.miniforge.event-stream.transition-keys :as tk]
   [ai.miniforge.event-stream.workflow-envelope :as envelope]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-transition-request
  [result]
  (get result tk/phase-transition-request-key))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} redirect-target
  "Project a redirect target for legacy consumers.

   The workflow runner now emits :phase/transition-request. This helper keeps
   :phase/redirect-to available only when the transition request represents a
   redirect, so older event consumers do not break while the newer event shape
   remains authoritative."
  [result]
  (let [request (phase-transition-request result)
        transition-type (get request tk/transition-type-key)]
    (when (= tk/redirect-transition-type transition-type)
      (get request tk/transition-target-key))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} phase-completed [stream workflow-id phase & [result]]
  (let [outcome (get result :outcome (when-not (current/active? stream) :success))
        request (phase-transition-request result)
        redirect-to (or (redirect-target result)
                        (:redirect-to result))
        message (msg/t :workflow/phase-completed {:phase (name phase) :outcome (some-> outcome name)})
        event (envelope/create stream :workflow/phase-completed workflow-id message {})]
    (if (anomaly/any-anomaly? event)
      event
      (-> event
          (assoc :workflow/phase phase
                 :phase/outcome outcome)
          (cond->
            (:duration-ms result) (assoc :phase/duration-ms (:duration-ms result))
            (:review-decision result) (assoc :phase/review-decision (:review-decision result))
            (:phase/blocked-reason result) (assoc :phase/blocked-reason (:phase/blocked-reason result))
            (:artifacts result) (assoc :phase/artifacts (:artifacts result))
            (:error result) (assoc :phase/error (:error result))
            request (assoc :phase/transition-request request)
            redirect-to (assoc :phase/redirect-to redirect-to)
            (:tokens result) (assoc :phase/tokens (:tokens result))
            (:cost-usd result) (assoc :phase/cost-usd (:cost-usd result))
            (:meta result) (assoc :phase/meta (:meta result))
            (:phase/termination-reason result)
            (assoc :phase/termination-reason (:phase/termination-reason result)))))))

(comment ::phase-completion)
