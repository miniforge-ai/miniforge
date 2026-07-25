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
(ns ai.miniforge.compliance-scanner.pr-review-test
  "Tests for the `pr-review` Layer 1 entry point (N13 §2.2).

   Locks in the read-only contract: `pr-review`
   - requires `:base-ref`,
   - delegates to `scan` with `:since` populated from `:base-ref`,
   - never invokes `execute!` (the auto-fix path),
   - returns the rendered comment vector + summary."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.compliance-scanner.interface :as scanner]
            [ai.miniforge.compliance-scanner.classify  :as classify-impl]
            [ai.miniforge.compliance-scanner.scan      :as scan-impl]
            [ai.miniforge.compliance-scanner.execute   :as execute-impl]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private one-violation
  {:rule/id        :ai.miniforge.standards/example-rule
   :rule/category  "code-quality"
   :rule/title     "Example rule"
   :file           "components/example/src/foo.clj"
   :line           7
   :current        "(throw (ex-info \"x\" {}))"
   :suggested      "(anomaly/throw-anomaly :foo/x {})"
   :auto-fixable?  true
   :rationale      "test fixture"})

(deftest ^{:stratum 0} base-ref-required
  (testing "missing :base-ref throws with anomaly tag"
    (let [thrown (try
                   (scanner/pr-review "/nonexistent" ".standards" {})
                   nil
                   (catch Exception e e))]
      (is (some? thrown))
      (is (= :pr-review/missing-base-ref
             (:anomaly (ex-data thrown))))))
  (testing "blank :base-ref also rejected"
    (is (thrown? Exception
                 (scanner/pr-review "/nonexistent" ".standards"
                                    {:base-ref ""})))))

(deftest ^{:stratum 0} empty-scan-returns-empty-comments
  (testing "no violations → empty comments vector + zeroed summary"
    (with-redefs [scan-impl/scan-repo (fn [_ _ _] {:violations       []
                                                   :rules-scanned    []
                                                   :files-scanned    0
                                                   :scan-duration-ms 1})
                  execute-impl/execute! (fn [& _] (throw (ex-info "should not be called" {})))]
      (let [{:pr-review/keys [comments summary]}
            (scanner/pr-review "/some/repo" ".standards"
                               {:base-ref "origin/main"})]
        (is (= [] comments))
        (is (zero? (:total summary)))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private fake-scan-result
  {:violations       [one-violation]
   :rules-scanned    [:ai.miniforge.standards/example-rule]
   :files-scanned    1
   :scan-duration-ms 1})

(deftest ^{:stratum 1} summary-counts
  (testing "summary aggregates total / auto-fixable / needs-review / files / rules"
    (let [v1 (assoc one-violation :file "a.clj" :rule/id :rule/a :auto-fixable? true)
          v2 (assoc one-violation :file "b.clj" :rule/id :rule/b :auto-fixable? false)
          v3 (assoc one-violation :file "a.clj" :rule/id :rule/a :auto-fixable? true)]
      (with-redefs [scan-impl/scan-repo (fn [_ _ _] {:violations       [v1 v2 v3]
                                                     :rules-scanned    [:rule/a :rule/b]
                                                     :files-scanned    2
                                                     :scan-duration-ms 1})
                    classify-impl/classify-violations identity
                    execute-impl/execute! (fn [& _] (throw (ex-info "should not be called" {})))]
        (let [{:pr-review/keys [summary]}
              (scanner/pr-review "/some/repo" ".standards"
                                 {:base-ref "origin/main"})]
          (is (= 3 (:total summary)))
          (is (= 2 (:auto-fixable summary)))
          (is (= 1 (:needs-review summary)))
          (is (= 2 (:files-affected summary)))
          (is (= 2 (:rules-violated summary))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} scan-invoked-with-since
  (testing "pr-review forwards :base-ref to scan as :since and never to execute!"
    (let [scan-calls    (atom [])
          execute-calls (atom 0)]
      (with-redefs [scan-impl/scan-repo
                    (fn [repo standards opts]
                      (swap! scan-calls conj
                             {:repo repo :standards standards :opts opts})
                      fake-scan-result)
                    execute-impl/execute!
                    (fn [& args]
                      (swap! execute-calls inc)
                      args)]
        (let [result (scanner/pr-review
                      "/some/repo" ".standards"
                      {:base-ref  "origin/main"
                       :rules     :all
                       :pack-info {:pack/id "test-pack" :pack/version "1.0.0"}})]
          (is (= 1 (count @scan-calls)))
          (is (= "origin/main" (get-in @scan-calls [0 :opts :since]))
              ":since opt is populated from :base-ref")
          (is (= :all (get-in @scan-calls [0 :opts :rules])))
          (is (zero? @execute-calls)
              "pr-review must not invoke execute! — read-only contract")
          (is (= fake-scan-result (:pr-review/scan-result result)))
          (is (= 1 (count (:pr-review/classified result))))
          (is (= 1 (count (:pr-review/comments result))))
          (is (= "test-pack"
                 (get-in result [:pr-review/comments 0
                                 :comment/payload :violation/pack-id])))
          (is (= 1 (get-in result [:pr-review/summary :total]))))))))

(deftest ^{:stratum 2} pack-info-default
  (testing "no :pack-info supplied → default pack-id/version filled in"
    (with-redefs [scan-impl/scan-repo (fn [_ _ _] fake-scan-result)
                  execute-impl/execute! (fn [& _] (throw (ex-info "should not be called" {})))]
      (let [result (scanner/pr-review
                    "/some/repo" ".standards"
                    {:base-ref "origin/main"})]
        (is (= "unknown" (get-in result [:pr-review/comments 0
                                         :comment/payload :violation/pack-id])))
        (is (= "0.0.0"   (get-in result [:pr-review/comments 0
                                         :comment/payload :violation/pack-version])))))))
