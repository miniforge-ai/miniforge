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
(ns ai.miniforge.phase-opsv.lifecycle
  "Shared phase-registry interceptors for the deterministic OPSV pipeline."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.events :as events]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-config
  {:agent nil
   :gates []
   :budget {:tokens 0 :iterations 1 :time-seconds 300}})

(defn- ^{:stratum 0} phase-result
  [output]
  (if (anomaly/anomaly? output)
    {:status :error
     :output output
     :error {:message (:anomaly/message output)
             :data (:anomaly/data output)}
     :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}
    {:status :success
     :output output
     :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}}))

(defn- ^{:stratum 0} isolate-runtime-adapter
  [ctx]
  (let [input (:execution/input ctx)]
    (if (contains? input :opsv/adapter)
      (let [adapter (:opsv/adapter input)
            durable-ctx (update ctx :execution/input dissoc :opsv/adapter)]
        (if (get-in ctx [:execution/opts :opsv/adapter])
          durable-ctx
          (assoc-in durable-ctx [:execution/opts :opsv/adapter] adapter)))
      ctx)))

(defn- ^{:stratum 0} ensure-evidence-assembly
  [ctx]
  (let [bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        durable-assembly (get-in ctx [:execution/input
                                      :opsv/evidence-assembly])
        supplied-store (or (:opsv/evidence-assembly-store ctx)
                           (get-in ctx [:execution/opts
                                        :opsv/evidence-assembly-store]))
        supplied-assembly (when (and bundle-id supplied-store)
                            (evidence/get-opsv-assembly supplied-store
                                                       bundle-id))]
    (cond
      (and supplied-assembly
           (= (:execution/id ctx)
              (:evidence-bundle/workflow-id supplied-assembly)))
      (-> ctx
          (assoc :opsv/evidence-assembly-store supplied-store)
          (assoc-in [:execution/input :opsv/evidence-assembly]
                    supplied-assembly))

      (and (= bundle-id (:evidence-bundle/id durable-assembly))
           (= (:execution/id ctx)
              (:evidence-bundle/workflow-id durable-assembly)))
      (assoc ctx :opsv/evidence-assembly-store
             (evidence/restore-opsv-assembly-store durable-assembly))

      bundle-id
      (anomaly/anomaly :invalid-input
                       "OPSV evidence identifier has no matching workflow assembly"
                       {:opsv/evidence-bundle-id bundle-id})

      :else
      (let [store (or supplied-store
                      (evidence/create-opsv-assembly-store))
          assembly (evidence/allocate-opsv-assembly! store
                                                     (:execution/id ctx))]
        (-> ctx
            (assoc :opsv/evidence-assembly-store store)
            (assoc-in [:execution/input :opsv/evidence-bundle-id]
                      (:evidence-bundle/id assembly))
            (assoc-in [:execution/input :opsv/evidence-assembly]
                      assembly))))))

(defn- ^{:stratum 0} persist-evidence-assembly
  [ctx]
  (let [store (:opsv/evidence-assembly-store ctx)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])]
    (if-let [assembly (and store bundle-id
                           (evidence/get-opsv-assembly store bundle-id))]
      (assoc-in ctx [:execution/input :opsv/evidence-assembly] assembly)
      ctx)))

(defn- ^{:stratum 0} error-phase
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} leave-phase
  [ctx]
  (let [phase-key (get-in ctx [:phase :name])
        result (get-in ctx [:phase :result])
        success? (= :success (:status result))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time (get-in ctx [:phase :started-at]))]
    (when success?
      (events/emit-phase-events! ctx phase-key (:output result)))
    (cond-> (-> ctx
                persist-evidence-assembly
                (assoc-in [:phase :ended-at] end-time)
                (assoc-in [:phase :duration-ms] duration-ms)
                (assoc-in [:phase :status] (if success? :completed :failed))
                (assoc-in [:phase :metrics] {:tokens 0 :cost-usd 0.0
                                             :duration-ms duration-ms})
                (assoc-in [:phase :result :metrics :duration-ms] duration-ms))
      success?
      (update-in [:execution :phases-completed] (fnil conj []) phase-key))))

(defn- ^{:stratum 1} enter-phase
  [ctx phase-key transform config]
  (let [runtime-ctx (isolate-runtime-adapter ctx)
        prepared-ctx (ensure-evidence-assembly runtime-ctx)
        start-time (System/currentTimeMillis)
        prepared? (not (anomaly/anomaly? prepared-ctx))
        result (phase-result (if prepared?
                               (transform prepared-ctx)
                               prepared-ctx))]
    (phase/enter-context (if prepared? prepared-ctx runtime-ctx) phase-key
                         (:agent config)
                         (:gates config) (:budget config)
                         start-time result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} interceptor
  [config phase-key transform]
  (let [merged (phase/merge-with-defaults config)]
    {:name phase-key
     :config merged
     :enter #(enter-phase % phase-key transform merged)
     :leave leave-phase
     :error error-phase}))
