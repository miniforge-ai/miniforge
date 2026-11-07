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
(ns ai.miniforge.agent-environment.fake.world
  "The fake provider's in-memory world: one map per namespace holding its
   UID and readiness.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} provision-steps
  "The steps a fake provision walks, in order."
  [:step/namespace :step/workload])

(def ^{:stratum 0} default-capabilities
  "Both isolation levels and generous ceilings, so tests opt into refusals."
  {:capabilities/provider :provider/fake
   :capabilities/isolation #{:isolation/shared-kernel :isolation/dedicated-kernel}
   :capabilities/max-limits {:limits/cpu-millicores 16000
                             :limits/memory-mib 65536
                             :limits/ephemeral-storage-mib 262144}})

(defn ^{:stratum 0} step [name outcome] {:step/name name :step/outcome outcome})

(defn ^{:stratum 0} live
  "The namespace `target` names, only while it still has `target`'s UID."
  [world target]
  (let [space (get world (:target/namespace target))]
    (when (and space (= (:target/uid target) (:uid space)))
      space)))

(defn ^{:stratum 0} provisioned
  [world namespace uid applied]
  (assoc world namespace {:uid uid
                          :ready? (contains? (set applied) :step/workload)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} walk-steps
  "Applied steps before `fail-step`, then the failed one; later steps are
   never attempted."
  [fail-step]
  (let [[applied failed] (split-with #(not= fail-step %) provision-steps)]
    {:applied (vec applied) :failed (first failed)}))

(defn ^{:stratum 1} deletable?
  "Delete only the namespace `target` provisioned. A cleanup that never
   learned the UID deletes by name, the only handle it has."
  [world target]
  (or (nil? (:target/uid target)) (some? (live world target))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} delete-outcome
  "What deleting `target` from `world` reports: an absent namespace is
   already gone, another UID's is refused, an unconfirmed delete is uncertain."
  [world target uncertain?]
  (cond
    (nil? (get world (:target/namespace target))) :outcome/applied
    (not (deletable? world target)) :outcome/failed
    uncertain? :outcome/uncertain
    :else :outcome/applied))

(defn ^{:stratum 2} delete
  "`world` after deleting `target`, only when the delete is confirmed."
  [world target uncertain?]
  (if (and (some? (get world (:target/namespace target)))
           (deletable? world target)
           (not uncertain?))
    (dissoc world (:target/namespace target))
    world))
