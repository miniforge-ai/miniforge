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

(ns ai.miniforge.progress-detector.detectors.repair-loop-test
  "Tests for the repair-loop detector.

   Pure-fingerprint cases ported verbatim from agent.reviewer-test
   (PR #762's regression suite). Detector-shaped cases exercise the
   protocol surface — observe, prior-state threading, anomaly
   emission, non-review event passthrough."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.detectors.repair-loop :as sut]
   [ai.miniforge.progress-detector.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures (lifted from PR #762's regression suite)

(def ^:private blocking-issue-a
  {:severity :blocking :file "src/foo.clj" :line 12 :description "Null pointer access"})

(def ^:private blocking-issue-b
  {:severity :blocking :file "src/bar.clj" :line 7  :description "Bad arity"})

(def ^:private warning-issue
  {:severity :warning :file "src/foo.clj" :line 90 :description "Inefficient loop"})

(def ^:private nit-issue
  {:severity :nit :file "src/foo.clj" :line 4 :description "Stale comment"})

(defn- review-event
  "Wrap a review map in the observation envelope the detector consumes."
  ([review]      (review-event review 1))
  ([review seq#] {:seq seq# :review/artifact review}))

(defn- count-anomalies [state]
  (count (:anomalies state)))

(defn- first-anomaly [state]
  (first (:anomalies state)))

;------------------------------------------------------------------------------ Layer 1
;; review-fingerprint — ported verbatim

(deftest review-fingerprint-includes-blocking-and-warning-test
  (testing "fingerprint covers actionable severities (:blocking + :warning)"
    (let [fp (sut/review-fingerprint
              {:review/issues [blocking-issue-a warning-issue]})]
      (is (= 2 (count fp)) ":blocking and :warning both flow through")
      (is (every? vector? fp) "each entry is a tuple"))))

(deftest review-fingerprint-excludes-nits-test
  (testing ":nit issues are excluded — long-tail polish, not stagnation"
    (let [fp (sut/review-fingerprint
              {:review/issues [blocking-issue-a nit-issue]})]
      (is (= 1 (count fp)) "only the blocking issue counted")
      (is (= :blocking (-> fp first first))))))

(deftest review-fingerprint-is-order-independent-test
  (testing "issue order does not affect the fingerprint"
    (let [fp1 (sut/review-fingerprint
               {:review/issues [blocking-issue-a blocking-issue-b]})
          fp2 (sut/review-fingerprint
               {:review/issues [blocking-issue-b blocking-issue-a]})]
      (is (= fp1 fp2)
          "fingerprint is sorted before comparison"))))

(deftest review-fingerprint-empty-when-no-actionable-issues-test
  (testing "approved review with only nits has empty fingerprint"
    (is (= [] (sut/review-fingerprint
               {:review/issues [nit-issue]})))
    (is (= [] (sut/review-fingerprint
               {:review/issues []})))))

(deftest review-fingerprint-discriminates-on-description-change-test
  (testing "fingerprint changes when an issue description changes"
    (let [original (sut/review-fingerprint
                    {:review/issues [blocking-issue-a]})
          edited   (sut/review-fingerprint
                    {:review/issues [(assoc blocking-issue-a
                                            :description "Different bug")]})]
      (is (not= original edited)
          "different description ⇒ different fingerprint"))))

(deftest review-fingerprint-survives-whitespace-reformatting-test
  (testing "trivial whitespace differences don't read as progress"
    (let [base     (sut/review-fingerprint
                    {:review/issues [(assoc blocking-issue-a
                                            :description "Null pointer access")]})
          reflowed (sut/review-fingerprint
                    {:review/issues [(assoc blocking-issue-a
                                            :description "  Null   pointer\n access  ")]})]
      (is (= base reflowed)
          "whitespace normalization keeps the fingerprint stable across reformats"))))

(deftest review-fingerprint-includes-failed-gates-test
  (testing "gate-only mode: failed gate-results contribute to the fingerprint"
    (let [fp (sut/review-fingerprint
              {:review/gate-results [{:gate-id :syntax
                                      :passed? false
                                      :errors [{:message "Unbalanced paren at line 8"}]}
                                     {:gate-id :lint
                                      :passed? true
                                      :errors []}]})]
      (is (= 1 (count fp)) "only the failed gate contributes")
      (is (= :blocking (-> fp first first))
          "failed gates fingerprint as :blocking severity")
      (is (= ":gate/syntax" (-> fp first second))
          "gate id is the file slot for sortable identification"))))

;------------------------------------------------------------------------------ Layer 1
;; stagnated? — ported verbatim

(deftest stagnated?-true-on-identical-fingerprints-test
  (testing "two consecutive identical fingerprints with non-empty issues ⇒ stagnated"
    (let [fp (sut/review-fingerprint
              {:review/issues [blocking-issue-a]})]
      (is (true? (sut/stagnated? fp fp))))))

(deftest stagnated?-false-on-first-iteration-test
  (testing "no prior fingerprint ⇒ not stagnated (first review never short-circuits)"
    (let [fp (sut/review-fingerprint
              {:review/issues [blocking-issue-a]})]
      (is (false? (boolean (sut/stagnated? nil fp)))))))

(deftest stagnated?-false-on-empty-fingerprint-test
  (testing "no actionable issues ⇒ not stagnated regardless of prior"
    (is (false? (boolean (sut/stagnated? [] []))))
    (is (false? (boolean (sut/stagnated?
                          (sut/review-fingerprint
                           {:review/issues [blocking-issue-a]})
                          []))))))

(deftest stagnated?-false-on-progress-test
  (testing "fingerprint changed between iterations ⇒ progress, not stagnated"
    (let [fp1 (sut/review-fingerprint
               {:review/issues [blocking-issue-a blocking-issue-b]})
          fp2 (sut/review-fingerprint
               {:review/issues [blocking-issue-b]})]
      (is (false? (boolean (sut/stagnated? fp1 fp2)))
          "one issue resolved between iterations is real progress"))))

;------------------------------------------------------------------------------ Layer 2
;; Detector protocol surface

(deftest first-review-no-anomaly-test
  (testing "first review never fires (no prior fingerprint)"
    (let [det   (sut/make-repair-loop-detector)
          state (-> (proto/init det {})
                    (#(proto/observe det %
                                     (review-event
                                      {:review/issues [blocking-issue-a]}))))]
      (is (zero? (count-anomalies state))))))

(deftest two-identical-reviews-fires-mechanical-test
  (testing "second review with same fingerprint as prior ⇒ mechanical anomaly"
    (let [det    (sut/make-repair-loop-detector)
          review {:review/issues [blocking-issue-a blocking-issue-b]}
          state  (-> (proto/init det {})
                     (#(proto/observe det % (review-event review 1)))
                     (#(proto/observe det % (review-event review 2))))
          a      (first-anomaly state)]
      (is (= 1 (count-anomalies state)))
      (is (= :mechanical (get-in a [:anomaly/data :anomaly/class])))
      (is (= :error      (get-in a [:anomaly/data :anomaly/severity])))
      (is (= :anomalies.review/stagnation
             (get-in a [:anomaly/data :anomaly/category])))
      (is (= :detector/repair-loop
             (get-in a [:anomaly/data :detector/kind]))))))

(deftest review-progress-suppresses-anomaly-test
  (testing "second review with different fingerprint ⇒ no anomaly"
    (let [det     (sut/make-repair-loop-detector)
          rev1    {:review/issues [blocking-issue-a blocking-issue-b]}
          rev2    {:review/issues [blocking-issue-b]}
          state   (-> (proto/init det {})
                      (#(proto/observe det % (review-event rev1 1)))
                      (#(proto/observe det % (review-event rev2 2))))]
      (is (zero? (count-anomalies state))
          "fingerprint changed ⇒ progress, not stagnation"))))

(deftest empty-current-fingerprint-suppresses-anomaly-test
  (testing "second review with no actionable issues ⇒ no anomaly"
    (let [det    (sut/make-repair-loop-detector)
          rev1   {:review/issues [blocking-issue-a]}
          rev2   {:review/issues [nit-issue]}
          state  (-> (proto/init det {})
                     (#(proto/observe det % (review-event rev1 1)))
                     (#(proto/observe det % (review-event rev2 2))))]
      (is (zero? (count-anomalies state))
          ":approved-equivalent (only nits) is progress"))))

(deftest non-review-event-passthrough-test
  (testing "events without :review/artifact are passed through unchanged"
    (let [det    (sut/make-repair-loop-detector)
          state0 (proto/init det {})
          tool-event {:tool/id :tool/Read :seq 1 :tool/input "x"}]
      (is (= state0 (proto/observe det state0 tool-event))
          "tool-event observation is a pure passthrough"))))

(deftest gate-only-mode-stagnation-test
  (testing "two consecutive failed gate-only reviews fire stagnation"
    (let [det    (sut/make-repair-loop-detector)
          review {:review/gate-results [{:gate-id :syntax
                                          :passed? false
                                          :errors [{:message "bad paren"}]}]}
          state  (-> (proto/init det {})
                     (#(proto/observe det % (review-event review 1)))
                     (#(proto/observe det % (review-event review 2))))]
      (is (= 1 (count-anomalies state))
          "gate-only mode also surfaces stagnation"))))

(deftest anomaly-evidence-bounded-test
  (testing "anomaly evidence carries summary + event-ids + fingerprint"
    (let [det    (sut/make-repair-loop-detector)
          review {:review/issues [blocking-issue-a]}
          state  (-> (proto/init det {})
                     (#(proto/observe det % (review-event review 1)))
                     (#(proto/observe det % (review-event review 2))))
          ev     (get-in (first-anomaly state) [:anomaly/data :anomaly/evidence])]
      (is (string? (:summary ev)))
      (is (vector? (:event-ids ev)))
      (is (string? (:fingerprint ev)))
      (is (true? (:redacted? ev))))))
