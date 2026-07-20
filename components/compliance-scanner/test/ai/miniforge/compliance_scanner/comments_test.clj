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

(ns ai.miniforge.compliance-scanner.comments-test
  "Tests for the violation comment renderer (N13 §2.3)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.miniforge.compliance-scanner.comments :as comments]))

(def ^:private base-pack-info
  {:pack/id "miniforge-standards" :pack/version "1.4.0"})

(def ^:private auto-fixable-violation
  {:rule/id        :ai.miniforge.standards/exceptions-as-data
   :rule/category  "code-quality"
   :rule/title     "Exceptions must be data, not exceptions"
   :file           "components/agent/src/ai/miniforge/agent/foo.clj"
   :line           42
   :current        "(throw (ex-info \"bad\" {}))"
   :suggested      "(anomaly/throw-anomaly :foo/bad-state {})"
   :auto-fixable?  true
   :rationale      "Throwing exceptions breaks effect-as-data."})

(def ^:private non-fixable-violation
  {:rule/id        :ai.miniforge.standards/no-credentials-in-source
   :rule/category  "security"
   :rule/title     "Hardcoded credentials"
   :file           "bases/cli/src/ai/miniforge/cli/main.clj"
   :line           17
   :current        "(def api-key \"sk-XXXX\")"
   :suggested      nil
   :auto-fixable?  false
   :rationale      "Manual review required for credential rotation."})

(deftest payload-shape-required-keys
  (testing "payload contains all N13 §2.3 keys"
    (let [p (comments/violation->payload auto-fixable-violation base-pack-info)]
      (is (= :ai.miniforge.standards/exceptions-as-data (:violation/rule-id p)))
      (is (true?  (:violation/auto-fixable? p)))
      (is (= "(anomaly/throw-anomaly :foo/bad-state {})" (:violation/suggested-fix p)))
      (is (= "Throwing exceptions breaks effect-as-data." (:violation/rationale p)))
      (is (= "miniforge-standards" (:violation/pack-id p)))
      (is (= "1.4.0" (:violation/pack-version p)))
      (is (#{:error :warning :info} (:violation/severity p))))))

(deftest payload-rationale-is-always-text
  (testing "absent and malformed rationales become empty strings"
    (doseq [violation [(dissoc auto-fixable-violation :rationale)
                       (assoc auto-fixable-violation :rationale nil)
                       (assoc auto-fixable-violation :rationale false)
                       (assoc auto-fixable-violation :rationale :not-text)
                       (assoc auto-fixable-violation :rationale 42)]]
      (is (= "" (:violation/rationale
                 (comments/violation->payload violation base-pack-info)))))))

(deftest payload-severity-inference
  (testing "auto-fixable + non-critical → :warning"
    (is (= :warning
           (:violation/severity
            (comments/violation->payload auto-fixable-violation base-pack-info)))))
  (testing "non-fixable + security category → :error"
    (is (= :error
           (:violation/severity
            (comments/violation->payload non-fixable-violation base-pack-info)))))
  (testing "non-fixable + non-critical → :warning (not :error)"
    (let [v (assoc auto-fixable-violation :auto-fixable? false)]
      (is (= :warning
             (:violation/severity
              (comments/violation->payload v base-pack-info))))))
  (testing "auto-fixable + critical category → :error (critical wins)"
    (let [v (assoc auto-fixable-violation
                   :rule/category "security")]
      (is (= :error
             (:violation/severity
              (comments/violation->payload v base-pack-info))))))
  (testing ":severity-override wins"
    (is (= :info
           (:violation/severity
            (comments/violation->payload
             (assoc auto-fixable-violation :severity-override :info)
             base-pack-info))))))

(deftest comment-record-shape
  (testing "comment record has the four required keys + payload"
    (let [c (comments/violation->comment auto-fixable-violation base-pack-info)]
      (is (= "miniforge-policy-evaluator[bot]" (:comment/author c)))
      (is (= (:file auto-fixable-violation) (:comment/path c)))
      (is (= (:line auto-fixable-violation) (:comment/line c)))
      (is (string? (:comment/body c)))
      (is (map? (:comment/payload c))))))

(deftest comment-body-embeds-edn-payload
  (testing "comment body contains a parseable :comment/payload EDN block"
    (let [c (comments/violation->comment auto-fixable-violation base-pack-info)
          body (:comment/body c)]
      (is (str/includes? body "```edn"))
      (is (str/includes? body ":comment/payload"))
      (is (str/includes? body ":violation/rule-id"))
      (is (str/includes? body "miniforge-policy-evaluator[bot]")))))

(deftest extract-payload-roundtrips
  (testing "rendered → extracted yields the same payload"
    (let [c   (comments/violation->comment auto-fixable-violation base-pack-info)
          got (comments/extract-payload (:comment/body c))]
      (is (= (:comment/payload c) got)))))

(deftest extract-payload-missing-returns-nil
  (testing "no payload block → nil"
    (is (nil? (comments/extract-payload "no payload here")))
    (is (nil? (comments/extract-payload nil)))))

(deftest bulk-render-stable-order
  (testing "violations are sorted by (path, line, rule-id) ascending"
    (let [vs [(assoc auto-fixable-violation :line 50)
              non-fixable-violation
              auto-fixable-violation]
          cs (comments/violations->comments vs base-pack-info)]
      (is (= 3 (count cs)))
      ;; bases/* sorts before components/*
      (is (str/starts-with? (:comment/path (first cs)) "bases/"))
      (let [components-comments (filter #(str/starts-with? (:comment/path %) "components/") cs)
            lines               (mapv :comment/line components-comments)]
        (is (= [42 50] lines))))))

(deftest auto-fixable-flag-preserved
  (testing "auto-fixable? boolean flows through to payload"
    (is (true?  (-> auto-fixable-violation
                    (comments/violation->comment base-pack-info)
                    :comment/payload
                    :violation/auto-fixable?)))
    (is (false? (-> non-fixable-violation
                    (comments/violation->comment base-pack-info)
                    :comment/payload
                    :violation/auto-fixable?)))))

(deftest pack-info-injected
  (testing "pack-id and pack-version are passed through"
    (let [p (comments/violation->payload auto-fixable-violation
                                          {:pack/id "custom-pack"
                                           :pack/version "9.9.9"})]
      (is (= "custom-pack" (:violation/pack-id p)))
      (is (= "9.9.9" (:violation/pack-version p))))))
