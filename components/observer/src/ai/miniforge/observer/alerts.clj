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
(ns ai.miniforge.observer.alerts
  (:require [ai.miniforge.observer.alert-conditions :as conditions]
            [ai.miniforge.observer.alert-presentation :refer [alert]]
            [ai.miniforge.observer.alert-spec :as spec]
            [ai.miniforge.observer.alert-state :as state]
            [ai.miniforge.observer.alert-timeouts :as timeouts]
            [ai.miniforge.observer.boundary.heartbeat-progress :as progress]
            [ai.miniforge.observer.heartbeat-observation :as heartbeat]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} AlertRule spec/AlertRule)

(def ^{:stratum 0} AlertMap spec/AlertMap)

(def ^{:stratum 0} AlertFired spec/AlertFired)

(def ^{:stratum 0} create-alert-state state/create)

(defn ^{:stratum 0} alert-fired [_stream workflow-id alert-map]
  (merge {:event/type :observer/alert-fired
          :event/workflow-id workflow-id
          :event/timestamp (java.util.Date.)}
         (select-keys alert-map [:alert/rule-id :alert/type :alert/severity
                                 :alert/message :alert/data :alert/timestamp])))

(defn- ^{:stratum 0} phase-gap [rule event]
  (when-let [data (conditions/phase-gap (get-in rule [:alert/threshold :threshold-ms])
                                      (:progress/gap-ms (progress/interpret event)))]
    [(alert rule data)]))

(defn- ^{:stratum 0} error-rate [rule state]
  (when-let [data (conditions/error-rate (get-in rule [:alert/threshold :threshold-pct])
                                       (:tool-outcomes @state))]
    [(alert rule data)]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} evaluate-rule [event state rule]
  (case (:alert/type rule)
    :phase-gap (phase-gap rule event)
    :tool-call-timeout (timeouts/evaluate rule event state)
    :tool-error-rate (error-rate rule state)
    nil))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate-rules [rules event state]
  (state/observe! state event)
  (if (and (= :workflow/phase-heartbeat (:event/type event))
           (heartbeat/record! state event))
    (into [] (mapcat (partial evaluate-rule event state)) rules)
    []))
