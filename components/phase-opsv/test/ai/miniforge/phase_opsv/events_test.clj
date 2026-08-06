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
(ns ai.miniforge.phase-opsv.events-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.phase-opsv.events :as events]
   [ai.miniforge.phase-opsv.lifecycle :as lifecycle]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} event-context
  [stream]
  {:execution/id (random-uuid)
   :execution/input {:opsv/evidence-bundle-id (random-uuid)}
   :event-stream stream})

(def ^{:stratum 0} ^:private planned-output
  {:opsv/experiment-pack-hash "pack-hash"
   :opsv/experiment-pack {:experiment-pack/targets
                          {:services ["catalog"]
                           :environments ["staging"]}}
   :opsv/risk-result {:score 0.1 :level :low :factors []}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} leave-planned-phase
  [ctx]
  (let [leave (:leave (lifecycle/interceptor {} :opsv/plan identity))]
    (leave (assoc ctx :phase
                  {:name :opsv/plan
                   :started-at (System/currentTimeMillis)
                   :result {:status :success :output planned-output}}))))

(deftest ^{:stratum 1} test-policy-confidence-degrades-safely
  (let [stream (event-stream/create-event-stream)]
    (events/emit-phase-events!
     (event-context stream)
     :opsv/synthesize
     {:opsv/policy-hash "policy-hash"
      :opsv/experiment-pack {:experiment-pack/convergence {}}
      :opsv/convergence-result {:evaluation {:confidence 0.9}}})
    (is (= :low (:opsv/confidence (first (event-stream/get-events stream)))))))

(deftest ^{:stratum 1} test-guardrail-abort-projection
  (let [stream (event-stream/create-event-stream)
        abort {:trigger :tail-latency
               :threshold {:milliseconds 500}
               :rollback-action :restore-previous-policy}]
    (events/emit-phase-events!
     (event-context stream)
     :opsv/execute
     {:opsv/experiment-pack-hash "pack-hash"
      :opsv/environment-fingerprint
      {:cluster "staging-1" :node-pools []
       :image-digests {} :config-hash "config-hash"}
      :opsv/ramp-steps
      [{:step/id "1"
        :step/intended-load {:requests-per-second 50}
        :step/observed-load {:requests-per-second 48}
        :step/metrics {:guardrail-abort? true}
        :step/guardrail-abort abort}]})
    (let [published (event-stream/get-events stream)
          abort-event (last published)]
      (testing "the load observation precedes the abort"
        (is (= [:opsv.experiment/started :opsv/load-step
                :opsv.guardrail/abort]
               (mapv :event/type published))))
      (testing "the canonical abort evidence is preserved"
        (is (= (:trigger abort) (:opsv/trigger abort-event)))
        (is (= (:threshold abort) (:opsv/threshold abort-event)))
        (is (= {} (:opsv/observed abort-event)))
        (is (= (:rollback-action abort)
               (:opsv/rollback-action abort-event)))))))

(deftest ^{:stratum 1} test-invalid-projection-is-not-published
  (let [stream (event-stream/create-event-stream)
        result (events/emit-phase-events!
                (event-context stream) :opsv/plan
                (dissoc planned-output :opsv/experiment-pack-hash))]
    (is (anomaly/anomaly? result))
    (is (empty? (event-stream/get-events stream)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-publication-rejection-fails-the-phase
  (let [stream (event-stream/create-event-stream)
        ctx (event-context stream)
        workflow-id (:execution/id ctx)
        _ (event-stream/quiesce! stream {:workflow-id workflow-id})
        completed (leave-planned-phase ctx)]
    (is (= :failed (get-in completed [:phase :status])))
    (is (anomaly/anomaly? (get-in completed [:phase :result :output])))
    (is (empty? (event-stream/get-events stream)))
    (is (not-any? #{:opsv/plan}
                  (get-in completed [:execution :phases-completed])))))

(deftest ^{:stratum 2} test-evidence-accumulation-failure-fails-the-phase
  (let [stream (event-stream/create-event-stream)
        ctx (assoc (event-context stream)
                   :opsv/evidence-assembly-store
                   (evidence/create-opsv-assembly-store))
        completed (leave-planned-phase ctx)]
    (is (= :failed (get-in completed [:phase :status])))
    (is (anomaly/anomaly? (get-in completed [:phase :result :output])))
    (is (= [:opsv.experiment/planned]
           (mapv :event/type (event-stream/get-events stream))))))
