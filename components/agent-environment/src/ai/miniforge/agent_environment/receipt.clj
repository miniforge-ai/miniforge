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
(ns ai.miniforge.agent-environment.receipt
  "Receipt construction. Every receipt names the pilot profile: nothing this
   contract emits is qualified until real-runtime qualification rows pass."
  (:require
   [ai.miniforge.agent-environment.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pilot-profile
  "The only profile the pilot may claim."
  :profile/unqualified)

(defn ^{:stratum 0} request-subject
  "Who asked and for what, before any environment exists. The tenant is the
   acting one, so a forged request is receipted to whoever sent it."
  [acting request]
  {:receipt/tenant-id (:acting/tenant-id acting)
   :receipt/principal-id (:acting/principal-id acting)
   :receipt/run-id (:environment/run-id request)
   :receipt/task-id (:environment/task-id request)
   :receipt/environment-uid nil
   :receipt/lease-generation nil})

(defn ^{:stratum 0} lease-subject
  [acting lease]
  {:receipt/tenant-id (:acting/tenant-id acting)
   :receipt/principal-id (:acting/principal-id acting)
   :receipt/run-id (:lease/run-id lease)
   :receipt/task-id (:lease/task-id lease)
   :receipt/environment-uid (:lease/environment-uid lease)
   :receipt/lease-generation (:lease/generation lease)})

(defn ^{:stratum 0} steps-outcome
  "Summarize provider steps: any failure after something took effect is
   partial; an unconfirmed step with no failure is uncertain."
  [steps]
  (let [seen (set (map :step/outcome steps))]
    (cond
      (every? #{:outcome/applied} seen) :outcome/applied
      (and (seen :outcome/failed) (or (seen :outcome/applied) (seen :outcome/uncertain))) :outcome/partial
      (seen :outcome/failed) :outcome/failed
      :else :outcome/uncertain)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} receipt
  [subject operation outcome steps reason at]
  (merge subject
         {:receipt/id (random-uuid)
          :receipt/contract-version schema/contract-version
          :receipt/profile pilot-profile
          :receipt/operation operation
          :receipt/outcome outcome
          :receipt/steps (vec steps)
          :receipt/reason reason
          :receipt/at at}))
