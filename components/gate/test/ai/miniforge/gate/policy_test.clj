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
(ns ai.miniforge.gate.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.messages :as msg]
            [ai.miniforge.gate.policy :as policy]
            [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0

;; -------------------------------------------------------------------------- check-review-approved
;; Cross-component contract: the reviewer agent (components/agent) produces an
;; artifact shaped like `build-review-artifact`'s output, with `:review/decision`
;; at the top level. The 2026-05-16 event-log-tool-visibility dogfood (root-
;; cause walk in docs/pull-requests/2026-05-16-fix-reviewer-issue-schema-nil-
;; tolerance.md) ran end-to-end and the reviewer emitted :approved, but a
;; strict ReviewIssue schema upstream silently flipped the decision to
;; :rejected. The gate side has always been correct; pin the contract from
;; this side so a future "fix" can't loosen check-review-approved into a
;; false positive.
(deftest ^{:stratum 0} check-review-approved-passes-on-explicit-approved
  (testing ":review/decision :approved → :passed? true"
    (is (= {:passed? true}
           (policy/check-review-approved
             {:review/decision :approved
              :review/summary "ok"
              :review/issues []}
             {})))))

(deftest ^{:stratum 0} check-review-approved-passes-on-conditionally-approved
  (testing ":review/decision :conditionally-approved → :passed? true"
    (is (= {:passed? true}
           (policy/check-review-approved
             {:review/decision :conditionally-approved}
             {})))))

(deftest ^{:stratum 0} check-review-approved-fails-on-changes-requested
  (let [result (policy/check-review-approved
                 {:review/decision :changes-requested}
                 {})]
    (is (false? (:passed? result)))
    (is (= :not-approved (-> result :errors first :type)))))

(deftest ^{:stratum 0} check-review-approved-fails-on-rejected
  (let [result (policy/check-review-approved
                 {:review/decision :rejected}
                 {})]
    (is (false? (:passed? result)))))

(deftest ^{:stratum 0} check-review-approved-fossil-paths-no-longer-honored
  (testing "legacy metadata-flag shapes are NO LONGER accepted — the verdict
            must be at the one canonical :review/decision location. A legacy
            :approved boolean without :review/decision fails closed (it can no
            longer silently approve an artifact that carries no real verdict)."
    (is (false? (:passed? (policy/check-review-approved {:metadata {:approved true}} {}))))
    (is (false? (:passed? (policy/check-review-approved {:artifact/metadata {:approved true}} {}))))
    (is (false? (:passed? (policy/check-review-approved {:review {:approved true}} {}))))))

(deftest ^{:stratum 0} check-review-approved-no-signal-fails-closed
  (testing "artifact with no decision and no metadata flag → :passed? false"
    (let [result (policy/check-review-approved {} {})]
      (is (false? (:passed? result))))))

;; -------------------------------------------------------------------------- check-policy-pack exception path
;; Regression: the catch branch previously returned {:passed? true …} — a
;; fail-open bug that let every artifact through whenever the policy loader
;; or evaluation crashed. Pin that it is now fail-closed and returns the
;; full docstring-promised result shape.
(deftest ^{:stratum 0} check-policy-pack-fails-closed-on-empty-packs
  (testing "no packs at the deploy gate blocks (fail-closed) — zero deploy policy
            is a misconfiguration, not a silent pass"
    (let [result (policy/check-policy-pack {:artifact/content "x"} {:policy-packs []})]
      (is (false? (:passed? result)))
      (is (= :no-policy-packs (-> result :errors first :type)))
      (is (empty? (:warnings result))))
    (testing "absent :policy-packs key is also empty → fail-closed"
      (let [result (policy/check-policy-pack {:artifact/content "x"} {})]
        (is (false? (:passed? result)))))))

(deftest ^{:stratum 0} check-policy-pack-fails-closed-on-exception
  (testing "any exception during evaluation blocks the artifact"
    (with-redefs [policy-pack/check-artifact
                  (fn [_packs _artifact _context]
                    (throw (RuntimeException. "policy loader crashed")))]
      (let [result (policy/check-policy-pack {:content "test"}
                                             {:policy-packs ["some-pack"]})]
        (is (false? (:passed? result)))
        (is (= :policy-check-error (-> result :errors first :type)))
        (is (= (msg/t :policy-pack/check-error {:message "policy loader crashed"})
               (-> result :errors first :message)))
        (is (empty? (:warnings result)))
        (is (empty? (:approval-required result)))))))

;; -------------------------------------------------------------------------- check-policy-pack enforcement-action classification
;; Regression: the gate must classify by :rule/enforcement :action, not severity.
;; A :high/:low rule and every content-scan violation (which carries no
;; :severity) previously fell through a severity cascade to a silent pass.
(def ^{:stratum 0} ^:private hard-halt-high-pack
  "One rule: :high severity, :hard-halt enforcement, content-scan detection (so
   the violation carries no :severity key). Must block the gate."
  {:pack/id      "test-deploy-block"
   :pack/version "2026.07.04"
   :pack/rules   [{:rule/id          :no-danger-token
                   :rule/severity    :high
                   :rule/detection   {:type :content-scan :pattern "DANGER"}
                   :rule/enforcement {:action  :hard-halt
                                      :message "Danger token present"}}]})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} warn-high-pack
  "As hard-halt-high-pack, but :warn enforcement — must pass (advisory)."
  []
  (assoc-in hard-halt-high-pack [:pack/rules 0 :rule/enforcement :action] :warn))

(deftest ^{:stratum 1} check-policy-pack-blocks-high-hard-halt-rule
  (testing ":high :hard-halt content-scan rule blocks the deploy gate"
    (let [result (policy/check-policy-pack
                  {:artifact/content "a line with DANGER in it" :artifact/path "main.tf"}
                  {:policy-packs [hard-halt-high-pack]})]
      (is (false? (:passed? result)))
      (is (= 1 (count (:errors result))))
      (is (= :no-danger-token (-> result :errors first :code)))
      (is (empty? (:approval-required result))))))

(deftest ^{:stratum 1} check-policy-pack-clean-artifact-passes
  (testing "no rule fires → gate passes clean"
    (let [result (policy/check-policy-pack
                  {:artifact/content "nothing to see here" :artifact/path "main.tf"}
                  {:policy-packs [hard-halt-high-pack]})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result))))))

(deftest ^{:stratum 1} check-policy-pack-blocks-require-approval-with-detail
  (testing ":require-approval blocks the gate AND lands in :errors, so the
            failure carries detail (check-gate emits failure events from :errors)"
    (let [pack   (assoc-in hard-halt-high-pack
                           [:pack/rules 0 :rule/enforcement :action] :require-approval)
          result (policy/check-policy-pack
                  {:artifact/content "a line with DANGER in it" :artifact/path "main.tf"}
                  {:policy-packs [pack]})]
      (is (false? (:passed? result)))
      (is (= 1 (count (:errors result))))
      (is (= :no-danger-token (-> result :errors first :code)))
      (is (= 1 (count (:approval-required result)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} check-policy-pack-passes-high-warn-rule
  (testing ":high :warn rule passes with a warning — severity does not gate"
    (let [result (policy/check-policy-pack
                  {:artifact/content "a line with DANGER in it" :artifact/path "main.tf"}
                  {:policy-packs [(warn-high-pack)]})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result)))
      (is (= 1 (count (:warnings result))))
      (is (= :no-danger-token (-> result :warnings first :code))))))
