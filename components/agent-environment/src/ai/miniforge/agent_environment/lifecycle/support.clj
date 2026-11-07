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
(ns ai.miniforge.agent-environment.lifecycle.support
  "Small pieces every lifecycle call shares: the controller's provider and
   clock, step and call shapes, and the receipt subject for a request."
  (:require
   [ai.miniforge.agent-environment.admission :as admission]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} provider [c] (:controller/provider c))

(defn ^{:stratum 0} now [c] ((:controller/clock c)))

(defn ^{:stratum 0} failure-step [reason] {:step/name reason :step/outcome :outcome/failed})

(defn ^{:stratum 0} artifact-step
  [stored]
  {:step/name :artifact/persisted
   :step/outcome (if (anomaly/anomaly? stored) :outcome/failed :outcome/applied)})

(defn ^{:stratum 0} as-call
  [result]
  (if (anomaly/anomaly? result)
    result
    {:call/value result :call/steps []}))

(defn ^{:stratum 0} environment
  [request target uid]
  (when uid
    {:environment/request request
     :environment/uid uid
     :environment/namespace (:target/namespace target)
     :environment/lease (lease/lease-for request uid (:target/generation target))}))

(defn ^{:stratum 0} unconfirmed-if-anonymous
  "A provision whose steps all applied but that reported no UID cannot be
   confirmed as any environment."
  [outcome environment]
  (if (and (nil? environment) (= :outcome/applied outcome)) :outcome/uncertain outcome))

(defn ^{:stratum 0} subject-of
  [acting request environment]
  (if environment
    (receipt/lease-subject acting (:environment/lease environment))
    (receipt/request-subject acting request)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} preflight-failures
  [c request]
  (let [declared (admission/capability-failures (protocol/capabilities (provider c)) request)]
    (if (seq declared) declared (protocol/probe (provider c) request))))

(defn ^{:stratum 1} persist-one
  [c lease target path]
  (anomaly/let-ok [text (protocol/read-file (provider c) target path)]
    ((:controller/persist! c) lease path text)))
