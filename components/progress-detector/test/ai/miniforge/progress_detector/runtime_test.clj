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

(ns ai.miniforge.progress-detector.runtime-test
  "Tests for the agent-run detector runtime."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.detectors.repair-loop :as repair]
   [ai.miniforge.progress-detector.detectors.tool-loop   :as tloop]
   [ai.miniforge.progress-detector.runtime               :as sut]
   [ai.miniforge.progress-detector.tool-profile          :as tp]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private now (java.time.Instant/now))

(defn- registry-with-read-stable []
  (let [reg (tp/make-registry)]
    (tp/register! reg
                  {:tool/id     :tool/Read
                   :determinism :stable-with-resource-version
                   :anomaly/categories #{:anomalies.agent/tool-loop}})
    reg))

(defn- registry-with-websearch-unstable []
  (let [reg (tp/make-registry)]
    (tp/register! reg
                  {:tool/id     :tool/WebSearch
                   :determinism :unstable
                   :anomaly/categories #{:anomalies.agent/tool-loop}})
    reg))

(defn- read-event [seq-num]
  {:tool/id   :tool/Read
   :seq       seq-num
   :timestamp now
   :tool/input            "src/foo.clj"
   :resource/version-hash "sha256:aaa"})

(defn- review-event [seq-num issues]
  {:seq seq-num
   :review/artifact {:review/issues issues}})

(def ^:private blocking-issue
  {:severity :blocking :file "src/foo.clj" :line 12 :description "needs guard"})

;------------------------------------------------------------------------------ Layer 1
;; Construction

(deftest empty-detectors-rejected-test
  (testing "make-runtime throws when no detectors are provided"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/make-runtime {:detectors []})))))

(deftest fresh-runtime-no-anomalies-test
  (testing "new runtime has empty anomalies and continues"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]})]
      (is (= [] (sut/current-anomalies rt)))
      (is (= [] (sut/new-anomalies rt)))
      (is (false? (sut/terminate? rt))))))

;------------------------------------------------------------------------------ Layer 2
;; Tool-loop end-to-end

(deftest tool-loop-end-to-end-terminates-test
  (testing "5 identical Reads + check → :terminate"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]
                :config    {:config/params {:threshold-n 5
                                            :window-size 10}}})]
      (doseq [i (range 1 7)]
        (sut/observe! rt (read-event i)))
      (let [decision (sut/check rt)]
        (is (= :terminate (:action decision)))
        (is (some? (:anomaly decision)))
        (is (= :mechanical
               (get-in (:anomaly decision)
                       [:anomaly/data :anomaly/class])))))))

(deftest tool-loop-below-threshold-continues-test
  (testing "4 identical Reads with default threshold 5 → :continue"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]})]
      (doseq [i (range 1 5)]
        (sut/observe! rt (read-event i)))
      (is (= :continue (:action (sut/check rt)))))))

;------------------------------------------------------------------------------ Layer 2
;; Heuristic anomaly does not terminate

(deftest heuristic-anomaly-warns-not-terminates-test
  (testing "WebSearch :unstable repeats fire heuristic — runtime warns, not terminates"
    (let [reg (registry-with-websearch-unstable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]
                :config    {:config/params {:threshold-n 3
                                            :window-size 5}}})]
      (doseq [i (range 1 4)]
        (sut/observe! rt {:tool/id   :tool/WebSearch
                          :seq       i
                          :timestamp now
                          :tool/input "claude code"}))
      (let [decision (sut/check rt)]
        (is (= :warn (:action decision)))
        (is (false? (sut/terminate? rt))
            "still false after check — heuristic policy is :warn")))))

;------------------------------------------------------------------------------ Layer 2
;; Repair-loop end-to-end

(deftest repair-loop-stagnation-terminates-test
  (testing "two consecutive identical reviews → :terminate via repair-loop"
    (let [rt (sut/make-runtime
              {:detectors [(repair/make-repair-loop-detector)]})]
      (sut/observe! rt (review-event 1 [blocking-issue]))
      (sut/observe! rt (review-event 2 [blocking-issue]))
      (let [decision (sut/check rt)]
        (is (= :terminate (:action decision)))
        (is (= :anomalies.review/stagnation
               (get-in (:anomaly decision)
                       [:anomaly/data :anomaly/category])))))))

;------------------------------------------------------------------------------ Layer 2
;; MultiDetector composition

(deftest multi-detector-composition-test
  (testing "tool-loop + repair-loop in one runtime; each fires in its lane"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)
                            (repair/make-repair-loop-detector)]
                :config    {:config/params {:threshold-n 5}}})]
      ;; Stream of mixed events: 5 reads (loop) + 2 reviews (stagnated)
      (doseq [i (range 1 7)]
        (sut/observe! rt (read-event i)))
      (sut/observe! rt (review-event 100 [blocking-issue]))
      (sut/observe! rt (review-event 101 [blocking-issue]))
      (is (>= (count (sut/current-anomalies rt)) 2)
          "at least one anomaly per detector"))))

;------------------------------------------------------------------------------ Layer 3
;; check semantics — seen-count window

(deftest check-marks-anomalies-as-seen-test
  (testing "back-to-back checks don't re-fire the same anomaly"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]
                :config    {:config/params {:threshold-n 5}}})]
      (doseq [i (range 1 7)]
        (sut/observe! rt (read-event i)))
      (let [first-decision (sut/check rt)
            second-decision (sut/check rt)]
        (is (= :terminate (:action first-decision)))
        (is (= :continue (:action second-decision))
            "second check sees no new anomalies — already acted on")))))

(deftest new-anomalies-windowing-test
  (testing "new-anomalies returns only anomalies emitted since last check"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]
                :config    {:config/params {:threshold-n 5}}})]
      (doseq [i (range 1 7)]
        (sut/observe! rt (read-event i)))
      (is (seq (sut/new-anomalies rt)))
      (sut/check rt)
      (is (empty? (sut/new-anomalies rt))
          "after check, the unseen-window is drained"))))

;------------------------------------------------------------------------------ Layer 3
;; Custom policy

(deftest custom-policy-overrides-default-test
  (testing "permissive policy ⇒ mechanical anomaly does not terminate"
    (let [reg (registry-with-read-stable)
          rt  (sut/make-runtime
               {:detectors [(tloop/make-tool-loop-detector reg)]
                :config    {:config/params {:threshold-n 5}}
                :policy    {:mechanical :warn :heuristic :warn}})]
      (doseq [i (range 1 7)]
        (sut/observe! rt (read-event i)))
      (is (= :warn (:action (sut/check rt)))
          "custom policy treats mechanical as :warn"))))
