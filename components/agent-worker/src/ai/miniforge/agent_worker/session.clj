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
(ns ai.miniforge.agent-worker.session
  "Pure session rules: building a binding, digesting the selected config,
   deciding what a resubmission does, and normalizing reported usage so an
   unknown quantity stays nil."
  (:require
   [ai.miniforge.agent-worker.schema :as schema]
   [ai.miniforge.content-hash.interface :as content-hash]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} config-digest [config] (content-hash/content-hash config))

(defn ^{:stratum 0} submission-key
  [binding]
  [(:worker/tenant-id binding) (:worker/run-id binding) (:worker/task-id binding) (:worker/attempt binding)])

(defn ^{:stratum 0} submission-decision
  "`:new` for an unseen attempt, `:duplicate` for the identical submission
   (reconcile with its session, never relaunch), else refused."
  [existing submission]
  (cond
    (nil? existing) {:decision/kind :new}
    (= submission (:record/submission existing)) {:decision/kind :duplicate}
    :else {:decision/kind :refuse :decision/reason :anomalies.agent-worker/conflicting-submission}))

(defn ^{:stratum 0} usage-status
  [present]
  (cond
    (empty? present) :usage/unknown
    (= (count schema/usage-quantities) (count present)) :usage/reported
    :else :usage/partial))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} bind
  "The binding for one attempt of `config` in the leased environment under
   `grant`."
  [acting lease grant config attempt]
  {:worker/contract-version schema/contract-version
   :worker/tenant-id (:lease/tenant-id lease)
   :worker/principal-id (:acting/principal-id acting)
   :worker/run-id (:lease/run-id lease)
   :worker/task-id (:lease/task-id lease)
   :worker/environment-uid (:lease/environment-uid lease)
   :worker/lease-generation (:lease/generation lease)
   :worker/grant-id (:grant/id grant)
   :worker/config-digest (config-digest config)
   :worker/attempt attempt})

(defn ^{:stratum 1} normalize-usage
  "A Usage from what the worker reported. A missing quantity is nil and the
   status says so; nothing defaults to zero."
  [reported]
  (let [present (into {} (filter (comp some? val)) (select-keys reported schema/usage-quantities))]
    (merge (zipmap schema/usage-quantities (repeat nil))
           present
           {:usage/status (usage-status present)})))
