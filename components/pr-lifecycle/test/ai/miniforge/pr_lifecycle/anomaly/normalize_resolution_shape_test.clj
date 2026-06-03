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

(ns ai.miniforge.pr-lifecycle.anomaly.normalize-resolution-shape-test
  "Locks the dual-shape anomaly detection in
   `merge/normalize-resolution-outcome` for the W2 anomaly
   convergence.

   The injected `resolve-fn` (workflow.merge-resolution/resolve-conflict!
   today) can return a terminal `:dag-multi-parent-unresolvable`
   anomaly. Until W2 batch 4 flips the workflow brick that source is
   legacy-shape (`:anomaly/category`); afterwards it is canonical
   (`:anomaly/type`). `normalize-resolution-outcome` must detect both
   so the controller never sees a bare anomaly map leak through the
   dag/ok-or-dag/err contract.

   Mirrors the dual-shape lock added in evidence-bundle (#1004) and
   the dispatch-key pattern in failure-classifier/classify-failure
   (#1001)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.conflict-resolution :as conflict-resolution]
   [ai.miniforge.pr-lifecycle.merge :as merge]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures + factories

(def ^:private unresolvable-message
  "Merge conflict could not be auto-resolved")

(def ^:private conflicting-readiness
  "Readiness map shaped like evaluate-merge-readiness output where the
   branch check failed via CONFLICTING (DIRTY mergeStateStatus) — the
   shape that triggers `attempt-conflict-resolution!`'s dispatch into
   the injected `resolve-fn`."
  {:ready?   false
   :checks   {:branch (dag/ok {:up-to-date? false
                               :raw "{\"mergeable\":\"CONFLICTING\",\"mergeStateStatus\":\"DIRTY\"}"})}
   :blocking [:branch-not-up-to-date]})

(def ^:private gh-pr-info-output
  (str "{\"number\":123,"
       "\"headRefName\":\"feat/x\","
       "\"baseRefName\":\"main\","
       "\"headRefOid\":\"abc1234\","
       "\"baseRefOid\":\"def5678\"}"))

(defn- legacy-unresolvable-anomaly
  "Legacy-shape (`:anomaly/category`) terminal anomaly — what
   workflow.merge-resolution returns today, pre W2 batch 4."
  []
  {:anomaly/category  :anomalies/dag-multi-parent-unresolvable
   :anomaly/message   unresolvable-message
   :resolution/reason :budget-exhausted})

(defn- canonical-unresolvable-anomaly
  "Canonical-shape (`:anomaly/type` + `:anomaly/subtype`) terminal
   anomaly — what workflow.merge-resolution returns after W2 batch 4
   flips the workflow brick."
  []
  (anomaly/sub-anomaly :conflict
                       :anomalies/dag-multi-parent-unresolvable
                       unresolvable-message
                       {:resolution/reason :budget-exhausted}))

(defn- merge-context
  "Context map for `merge/attempt-merge` with an injected no-op
   resolve-fn (the real one is stubbed via with-redefs of
   `conflict-resolution/resolve-pr-conflicts!`)."
  []
  {:dag-id     (random-uuid)
   :run-id     (random-uuid)
   :task-id    (random-uuid)
   :pr-id      123
   :resolve-fn (fn [_] (dag/ok {}))})

(defn- attempt-merge-with-resolution
  "Run `merge/attempt-merge` with `resolve-pr-conflicts!` stubbed to
   return the supplied terminal anomaly. Returns the attempt-merge
   result for the caller to assert on."
  [terminal-anomaly]
  (with-redefs [merge/evaluate-merge-readiness
                (fn [_ _ _] conflicting-readiness)
                conflict-resolution/resolve-pr-conflicts!
                (fn [_] terminal-anomaly)
                merge/run-gh-command
                (fn [_ _] (dag/ok {:output gh-pr-info-output}))]
    (merge/attempt-merge "/tmp" 123
                         merge/default-merge-policy
                         (merge-context))))

;------------------------------------------------------------------------------ Layer 1
;; Dual-shape detection

(deftest legacy-shape-anomaly-normalizes-to-conflict-unresolvable
  (testing "legacy `:anomaly/category` terminal anomaly normalizes
            to a dag/err with :conflict-unresolvable code"
    (let [terminal (legacy-unresolvable-anomaly)
          result   (attempt-merge-with-resolution terminal)]
      (is (dag/err? result))
      (is (= :conflict-unresolvable (:code (:error result))))
      (is (= terminal (get-in result [:error :data :anomaly]))
          "original anomaly preserved for diagnostic surfacing"))))

(deftest canonical-shape-anomaly-normalizes-to-conflict-unresolvable
  (testing "canonical `:anomaly/type` terminal anomaly (post W2
            batch 4 workflow flip) also normalizes to a dag/err with
            :conflict-unresolvable code"
    (let [terminal (canonical-unresolvable-anomaly)
          result   (attempt-merge-with-resolution terminal)]
      (is (dag/err? result))
      (is (= :conflict-unresolvable (:code (:error result))))
      (is (= terminal (get-in result [:error :data :anomaly]))
          "original anomaly preserved for diagnostic surfacing")
      (is (= unresolvable-message (:message (:error result)))
          ":anomaly/message carries through to the dag/err message"))))

(deftest canonical-and-legacy-shapes-both-detected-by-any-anomaly
  (testing "both shapes that `any-anomaly?` must recognise yield the
            same downstream :conflict-unresolvable normalization. This
            locks the dual-shape contract: if a future change drops
            one of the two predicate branches, the corresponding
            terminal anomaly leaks through and one of these
            assertions fires."
    (doseq [terminal [(legacy-unresolvable-anomaly)
                      (canonical-unresolvable-anomaly)]]
      (let [result (attempt-merge-with-resolution terminal)]
        (is (dag/err? result)
            "terminal anomaly is normalized into dag/err for both shapes")
        (is (= :conflict-unresolvable (:code (:error result)))
            ":conflict-unresolvable code is emitted for both shapes")))))
