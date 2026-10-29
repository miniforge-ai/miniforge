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
;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-cleanup-test
  "Tests for post-workflow cleanup hooks."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.cleanup-signals :as signals]
   [ai.miniforge.workflow.runner-cleanup :as cleanup]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Signal factories
;; ============================================================================
(def ^{:stratum 0} ^:private make-completion-signal signals/completion)

(def ^{:stratum 0} ^:private make-exception-signal signals/failure)

(defn- ^{:stratum 0} execution-context [status]
  {:execution/status status
   :execution/id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"})

(defn- ^{:stratum 0} workflow [] {:workflow/id :test-wf})

;; ============================================================================
;; promote-mature-learnings!
;; ============================================================================
(deftest ^{:stratum 0} promote-mature-learnings-nil-store-test
  (testing "returns empty vector when knowledge-store is nil"
    (is (= [] (cleanup/promote-mature-learnings! nil)))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; observe-workflow-signal!
;; ============================================================================
(deftest ^{:stratum 1} observe-workflow-signal-calls-observer-test
  (testing "passes signal to observe-signal-fn"
    (let [captured (atom nil)
          observe (partial reset! captured)
          opts {:observe-signal-fn observe}
          ctx (execution-context :completed)]
      (cleanup/observe-workflow-signal! opts ctx {} nil)
      (is (= :workflow-complete (:signal/type @captured))))))

(deftest ^{:stratum 1} observe-workflow-signal-noop-without-fn-test
  (testing "no-ops when observe-signal-fn is absent"
    (is (nil? (cleanup/observe-workflow-signal! {} (execution-context :completed) {} nil)))))

(deftest ^{:stratum 1} make-completion-signal-success-test
  (testing "builds :workflow-complete signal for succeeded context"
    (let [ctx (assoc (execution-context :completed)
                     :execution/phase-results {:plan {:status :completed}}
                     :execution/metrics {:tokens 100})
          signal (make-completion-signal ctx)]
      (is (= :workflow-complete (:signal/type signal)))
      (is (= (:execution/id ctx) (:workflow-id signal)))
      (is (= (:execution/phase-results ctx) (:phase-results signal)))
      (is (= (:execution/metrics ctx) (:metrics signal)))
      (is (inst? (:timestamp signal))))))

(deftest ^{:stratum 1} make-completion-signal-failure-test
  (testing "builds :workflow-failed signal for failed context"
    (let [ctx (execution-context :failed)
          signal (make-completion-signal ctx)]
      (is (= :workflow-failed (:signal/type signal))))))

(deftest ^{:stratum 1} make-exception-signal-test
  (testing "builds minimal failure signal from workflow and exception"
    (let [wf (workflow)
          ex (ex-info "boom" {:code 500})
          signal (make-exception-signal wf ex)]
      (is (= :workflow-failed (:signal/type signal)))
      (is (= :test-wf (:workflow-id signal)))
      (is (= "boom" (:error signal))))))

(deftest ^{:stratum 1} make-exception-signal-nil-exception-test
  (testing "handles nil exception gracefully"
    (let [signal (make-exception-signal (workflow) nil)]
      (is (nil? (:error signal))))))

(comment ::cleanup-contract-tests)
