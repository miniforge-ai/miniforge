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
(ns ai.miniforge.workflow.phase-metrics-single-count-test
  "A phase's spend reaches :execution/metrics once. The software-factory
   :leave fns add [:phase :metrics] to :execution/metrics themselves, and
   the runner merged [:phase :metrics] again after :leave."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.isolation-test-support :as isolation]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} probe-budget {:tokens 1 :iterations 1 :time-seconds 1})

(def ^{:stratum 0} spend {:tokens 100 :cost-usd 0.25 :duration-ms 0})

(defn ^{:stratum 0} run-metrics
  [phase-kws]
  (:execution/metrics
   (runner/run-pipeline {:workflow/id :single-count
                         :workflow/version "1.0.0"
                         :workflow/pipeline (mapv #(hash-map :phase %) phase-kws)}
                        {:task "probe"})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} completed-with-spend
  "Complete the phase reporting `spend` in [:phase :metrics]."
  [ctx]
  (-> ctx
      (assoc-in [:phase :status] :completed)
      (assoc-in [:phase :metrics] spend)
      (assoc-in [:phase :result] (phase/success nil "ok" {:tokens (:tokens spend)}))))

(defn ^{:stratum 1} add-spend-to-execution
  "What leave-plan, leave-implement, leave-review, leave-verify and
   leave-release also do with the same metrics."
  [ctx]
  (-> ctx
      (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens spend))
      (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (:cost-usd spend))))

(defn ^{:stratum 1} probe-interceptor
  [phase-kw config leave]
  {:name phase-kw
   :config (phase/merge-with-defaults config)
   :enter #(phase/enter-context % phase-kw :workflow-tester [] probe-budget
                                (System/currentTimeMillis) nil)
   :leave leave
   :error (fn [ctx _ex] ctx)})

(deftest ^{:stratum 1} self-accumulating-phase-counts-once-test
  (testing "a :leave that adds its own spend is not counted again by the runner"
    (let [metrics (run-metrics [:self-accumulating-probe])]
      (is (= 100 (:tokens metrics)))
      (is (= 0.25 (:cost-usd metrics))))))

(deftest ^{:stratum 1} reporting-phase-still-counts-test
  (testing "a phase that only reports [:phase :metrics] is still counted"
    (let [metrics (run-metrics [:reporting-probe])]
      (is (= 100 (:tokens metrics)))
      (is (= 0.25 (:cost-usd metrics))))))

(deftest ^{:stratum 1} mixed-pipeline-sums-each-phase-once-test
  (testing "two phases, one of each kind, sum to two phases' spend"
    (let [metrics (run-metrics [:self-accumulating-probe :reporting-probe])]
      (is (= 200 (:tokens metrics)))
      (is (= 0.5 (:cost-usd metrics))))))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} phase/get-phase-interceptor-method :self-accumulating-probe
  [config]
  (probe-interceptor :self-accumulating-probe config
                     (comp add-spend-to-execution completed-with-spend)))

(defmethod ^{:stratum 2} phase/get-phase-interceptor-method :reporting-probe
  [config]
  (probe-interceptor :reporting-probe config completed-with-spend))

(doseq [phase-kw [:self-accumulating-probe :reporting-probe]]
  (phase/register-phase-defaults! phase-kw {:phase phase-kw :agent :workflow-tester
                                            :gates [] :budget probe-budget}))

(use-fixtures :once isolation/with-isolated-host)
