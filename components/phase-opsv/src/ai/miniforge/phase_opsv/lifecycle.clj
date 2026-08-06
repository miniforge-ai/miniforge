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

(defn- ^{:stratum 0} ensure-evidence-assembly
  [ctx]
  (if (get-in ctx [:execution/input :opsv/evidence-bundle-id])
    ctx
    (let [store (or (get-in ctx [:execution/input
                                 :opsv/evidence-assembly-store])
                    (evidence/create-opsv-assembly-store))
          assembly (evidence/allocate-opsv-assembly! store
                                                     (:execution/id ctx))]
      (-> ctx
          (assoc-in [:execution/input :opsv/evidence-assembly-store] store)
          (assoc-in [:execution/input :opsv/evidence-bundle-id]
                    (:evidence-bundle/id assembly))))))

(defn- ^{:stratum 0} leave-phase
  [ctx]
  (let [phase-key (get-in ctx [:phase :name])
        result (get-in ctx [:phase :result])
        success? (= :success (:status result))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time (get-in ctx [:phase :started-at]))]
    (when success?
      (events/emit-phase-events! ctx phase-key (:output result)))
    (cond-> (-> ctx
                (assoc-in [:phase :ended-at] end-time)
                (assoc-in [:phase :duration-ms] duration-ms)
                (assoc-in [:phase :status] (if success? :completed :failed))
                (assoc-in [:phase :metrics] {:tokens 0 :cost-usd 0.0
                                             :duration-ms duration-ms})
                (assoc-in [:phase :result :metrics :duration-ms] duration-ms))
      success?
      (update-in [:execution :phases-completed] (fnil conj []) phase-key))))

(defn- ^{:stratum 0} error-phase
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enter-phase
  [ctx phase-key transform config]
  (let [prepared-ctx (cond-> ctx
                       (= :opsv/discover phase-key) ensure-evidence-assembly)
        start-time (System/currentTimeMillis)
        result (phase-result (transform prepared-ctx))]
    (phase/enter-context prepared-ctx phase-key nil
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
