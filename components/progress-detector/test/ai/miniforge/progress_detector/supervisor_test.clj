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

(ns ai.miniforge.progress-detector.supervisor-test
  "Tests for the supervisor — covers Stage 2 class-default policy and
   Stage 3 per-category on-anomaly overrides."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.supervisor :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Anomaly fixtures — minimal shape needed to exercise the supervisor

(defn- mk-anomaly
  "Construct a minimal anomaly map under :anomaly/data — the supervisor
   only reads :anomaly/class, :anomaly/category, :anomaly/severity, and the
   :anomaly/evidence :event-ids vector for tie-breaking."
  ([anomaly-class severity]
   (mk-anomaly anomaly-class severity nil nil))
  ([anomaly-class severity event-seq]
   (mk-anomaly anomaly-class severity event-seq nil))
  ([anomaly-class severity event-seq category]
   {:anomaly/type :fault
    :anomaly/data
    (cond-> {:detector/kind    :detector/test
             :anomaly/class    anomaly-class
             :anomaly/severity severity
             :anomaly/evidence {:summary    (str (name anomaly-class) " " (name severity))
                                :event-ids  (cond-> [] event-seq (conj event-seq))}}
      category (assoc :anomaly/category category))}))

;------------------------------------------------------------------------------ Layer 1
;; Empty + class-default policy

(deftest empty-anomalies-continue-test
  (testing "no anomalies ⇒ :continue, no controlling anomaly"
    (let [decision (sut/handle [])]
      (is (= :continue (:action decision)))
      (is (= [] (:anomalies decision)))
      (is (nil? (:anomaly decision))))))

(deftest mechanical-error-terminates-test
  (testing "single mechanical :error ⇒ :terminate"
    (let [a        (mk-anomaly :mechanical :error 1)
          decision (sut/handle [a])]
      (is (= :terminate (:action decision)))
      (is (= a (:anomaly decision)))
      (is (string? (:reason decision)))
      (is (sut/terminate? decision)))))

(deftest heuristic-warn-does-not-terminate-test
  (testing "single heuristic :warn ⇒ :warn (logged, not terminated)"
    (let [a        (mk-anomaly :heuristic :warn 1)
          decision (sut/handle [a])]
      (is (= :warn (:action decision)))
      (is (false? (sut/terminate? decision)))
      (is (= a (:anomaly decision))))))

(deftest fatal-mechanical-terminates-test
  (testing "fatal severity terminates regardless"
    (let [a        (mk-anomaly :mechanical :fatal 1)
          decision (sut/handle [a])]
      (is (= :terminate (:action decision))))))

(deftest custom-policy-overrides-default-test
  (testing "caller-supplied policy wins over default"
    (let [permissive {:mechanical :warn :heuristic :warn}
          a          (mk-anomaly :mechanical :error 1)
          decision   (sut/handle permissive [a])]
      (is (= :warn (:action decision))
          "custom policy treats mechanical anomalies as warn"))))

;------------------------------------------------------------------------------ Layer 2
;; Controlling-anomaly selection

(deftest severity-rank-fatal-beats-error-test
  (testing ":fatal > :error > :warn > :info"
    (let [info  (mk-anomaly :mechanical :info  1)
          warn  (mk-anomaly :mechanical :warn  2)
          err   (mk-anomaly :mechanical :error 3)
          fatal (mk-anomaly :mechanical :fatal 4)]
      (is (= fatal (sut/select-controlling [info warn err fatal])))
      (is (= err   (sut/select-controlling [info warn err])))
      (is (= warn  (sut/select-controlling [info warn]))))))

(deftest class-rank-mechanical-beats-heuristic-test
  (testing "ties on severity broken by :mechanical > :heuristic"
    (let [heur (mk-anomaly :heuristic  :error 1)
          mech (mk-anomaly :mechanical :error 2)]
      (is (= mech (sut/select-controlling [heur mech])))
      (is (= mech (sut/select-controlling [mech heur]))))))

(deftest seq-tiebreak-earliest-wins-test
  (testing "ties on severity AND class broken by earliest :event/seq"
    (let [later   (mk-anomaly :mechanical :error 50)
          earlier (mk-anomaly :mechanical :error 10)]
      (is (= earlier (sut/select-controlling [later earlier])))
      (is (= earlier (sut/select-controlling [earlier later]))))))

(deftest seq-tiebreak-missing-loses-test
  (testing "anomalies with event-ids beat anomalies without"
    (let [no-seq    (mk-anomaly :mechanical :error nil)
          with-seq  (mk-anomaly :mechanical :error 99)]
      (is (= with-seq (sut/select-controlling [no-seq with-seq]))
          "missing :event-ids treated as MAX_VALUE per design"))))

;------------------------------------------------------------------------------ Layer 2
;; Decision map shape

(deftest decision-includes-all-anomalies-test
  (testing "decision :anomalies contains every input anomaly, not just controlling"
    (let [a1       (mk-anomaly :mechanical :error 1)
          a2       (mk-anomaly :heuristic  :warn  2)
          a3       (mk-anomaly :heuristic  :info  3)
          decision (sut/handle [a1 a2 a3])]
      (is (= 3 (count (:anomalies decision)))
          "all anomalies surfaced; supervisor only chooses controlling")
      (is (= a1 (:anomaly decision))
          "controlling is the strongest signal"))))

(deftest reason-only-on-terminate-test
  (testing ":reason key is present only when :action is :terminate"
    (let [terminate-decision (sut/handle [(mk-anomaly :mechanical :error 1)])
          warn-decision      (sut/handle [(mk-anomaly :heuristic :warn 1)])
          continue-decision  (sut/handle [])]
      (is (contains? terminate-decision :reason))
      (is (not (contains? warn-decision :reason)))
      (is (not (contains? continue-decision :reason))))))

;------------------------------------------------------------------------------ Layer 3
;; 3-arity handle — on-anomaly category overrides

(deftest on-anomaly-category-overrides-class-default-test
  (testing "category lookup in on-anomaly takes precedence over class-default policy"
    (let [category :anomalies.agent/tool-loop
          ;; mechanical error would normally :terminate per default-policy
          a        (mk-anomaly :mechanical :error 1 category)
          ;; but on-anomaly says :continue for this category
          decision (sut/handle sut/default-policy
                               {category :continue}
                               [a])]
      (is (= :continue (:action decision))
          "on-anomaly category override suppresses class-default :terminate"))))

(deftest on-anomaly-terminate-overrides-class-warn-test
  (testing "category lookup can escalate action beyond class-default"
    (let [category :anomalies.review/stagnation
          ;; heuristic warn would normally :warn per default-policy
          a        (mk-anomaly :heuristic :warn 1 category)
          ;; on-anomaly escalates this category to :terminate
          decision (sut/handle sut/default-policy
                               {category :terminate}
                               [a])]
      (is (= :terminate (:action decision))
          "on-anomaly can escalate a heuristic to :terminate")
      (is (string? (:reason decision))
          ":reason present when action is :terminate"))))

(deftest on-anomaly-empty-falls-back-to-policy-test
  (testing "empty on-anomaly map falls back to class-default policy"
    (let [a        (mk-anomaly :mechanical :error 1 :anomalies.agent/tool-loop)
          decision (sut/handle sut/default-policy {} [a])]
      (is (= :terminate (:action decision))
          "empty on-anomaly map ⇒ class-default :terminate"))))

(deftest on-anomaly-missing-category-falls-back-to-policy-test
  (testing "category not in on-anomaly falls back to class-default policy"
    (let [a        (mk-anomaly :mechanical :error 1 :anomalies.agent/tool-loop)
          ;; on-anomaly only covers a different category
          decision (sut/handle sut/default-policy
                               {:anomalies.review/stagnation :continue}
                               [a])]
      (is (= :terminate (:action decision))
          "unmatched category falls through to class-default policy"))))

(deftest on-anomaly-nil-category-falls-back-to-policy-test
  (testing "anomaly with no category key falls back to class-default policy"
    (let [;; mk-anomaly with nil category omits :anomaly/category from data
          a        (mk-anomaly :mechanical :error 1)
          decision (sut/handle sut/default-policy
                               {:anomalies.agent/tool-loop :continue}
                               [a])]
      (is (= :terminate (:action decision))
          "nil category does not match any on-anomaly key; policy used"))))

(deftest on-anomaly-resolution-order-category-first-test
  (testing "full resolution chain: category → policy → :continue"
    (let [category :anomalies.agent/tool-loop
          ;; anomaly whose class is not in policy at all (:exotic)
          ;; but category IS in on-anomaly
          a        {:anomaly/type :fault
                    :anomaly/data {:anomaly/class    :exotic
                                   :anomaly/category category
                                   :anomaly/severity :error
                                   :anomaly/evidence {:summary "test" :event-ids [1]}}}]
      ;; Step 1: category found → :terminate
      (is (= :terminate
             (:action (sut/handle {} {category :terminate} [a])))
          "category hit → returned directly")
      ;; Step 2: category miss, class found → :warn
      (is (= :warn
             (:action (sut/handle {:exotic :warn} {} [a])))
          "class-default hit when category misses")
      ;; Step 3: both miss → :continue
      (is (= :continue
             (:action (sut/handle {} {} [a])))
          "nothing matches → :continue"))))

(deftest two-arity-delegates-to-three-arity-test
  (testing "2-arity (handle policy anomalies) is back-compat delegate to 3-arity"
    ;; The 2-arity and 3-arity with empty on-anomaly must produce identical decisions.
    (let [a        (mk-anomaly :mechanical :error 1 :anomalies.agent/tool-loop)
          via-two  (sut/handle sut/default-policy [a])
          via-three (sut/handle sut/default-policy {} [a])]
      (is (= (:action via-two) (:action via-three)))
      (is (= (:anomaly via-two) (:anomaly via-three))))))

(deftest three-arity-empty-input-continues-test
  (testing "3-arity handle with empty anomalies always returns :continue"
    (let [decision (sut/handle sut/default-policy
                               {:anomalies.agent/tool-loop :terminate}
                               [])]
      (is (= :continue (:action decision)))
      (is (= [] (:anomalies decision))))))
