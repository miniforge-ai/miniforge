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
(ns ai.miniforge.phase-opsv.evidence-runtime
  "Allocate, restore, and persist run-scoped OPSV evidence assembly state."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assembly-for-workflow?
  [assembly workflow-id]
  (and assembly (= workflow-id (:evidence-bundle/workflow-id assembly))))

(defn- ^{:stratum 0} with-supplied-assembly
  [ctx store assembly]
  (-> ctx
      (assoc :opsv/evidence-assembly-store store)
      (assoc-in [:execution/input :opsv/evidence-assembly] assembly)))

(defn- ^{:stratum 0} with-restored-assembly
  [ctx assembly]
  (assoc ctx :opsv/evidence-assembly-store
         (evidence/restore-opsv-assembly-store assembly)))

(defn- ^{:stratum 0} unmatched-evidence-id
  [bundle-id]
  (anomaly/anomaly :invalid-input
                   (msg/ts :evidence/assembly-mismatch)
                   {:opsv/evidence-bundle-id bundle-id}))

(defn- ^{:stratum 0} allocate-assembly
  [ctx supplied-store]
  (let [store (or supplied-store (evidence/create-opsv-assembly-store))
        assembly (evidence/allocate-opsv-assembly! store (:execution/id ctx))]
    (-> ctx
        (assoc :opsv/evidence-assembly-store store)
        (assoc-in [:execution/input :opsv/evidence-bundle-id]
                  (:evidence-bundle/id assembly))
        (assoc-in [:execution/input :opsv/evidence-assembly] assembly))))

(defn ^{:stratum 0} persist
  [ctx]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])]
    (if-let [assembly (and store bundle-id
                           (evidence/get-opsv-assembly store bundle-id))]
      (assoc-in ctx [:execution/input :opsv/evidence-assembly] assembly)
      ctx)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ensure-assembly
  [ctx]
  (let [bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        durable-assembly (get-in ctx [:execution/input :opsv/evidence-assembly])
        supplied-store (or (:opsv/evidence-assembly-store ctx)
                           (get-in ctx [:execution/opts
                                        :opsv/evidence-assembly-store]))
        supplied-assembly (when (and bundle-id supplied-store)
                            (evidence/get-opsv-assembly supplied-store
                                                       bundle-id))]
    (cond
      (assembly-for-workflow? supplied-assembly (:execution/id ctx))
      (with-supplied-assembly ctx supplied-store supplied-assembly)

      (and (= bundle-id (:evidence-bundle/id durable-assembly))
           (assembly-for-workflow? durable-assembly (:execution/id ctx)))
      (with-restored-assembly ctx durable-assembly)

      bundle-id (unmatched-evidence-id bundle-id)
      :else (allocate-assembly ctx supplied-store))))
