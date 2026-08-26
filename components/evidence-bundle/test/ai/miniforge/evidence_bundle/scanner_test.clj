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
(ns ai.miniforge.evidence-bundle.scanner-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.redaction.interface :as redaction]
   [ai.miniforge.evidence-bundle.scanner :as scanner]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} scan-artifact-reports-finding-types-only
  (testing "sensitive values are detected but not copied into evidence"
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "Contact alice@example.com"}})
          metadata (scanner/compliance-metadata result)]
      (is (= [{:finding/type :email}] (:scan/findings result)))
      (is (= {:evidence/contains-pii? true
              :compliance/sensitive-findings [{:finding/type :email}]}
             metadata)))))

(deftest ^{:stratum 0} compliance-metadata-is-empty-without-findings
  (testing "absence of findings does not overwrite caller-provided compliance flags"
    (is (= {} (scanner/compliance-metadata (scanner/scan-artifact
                                            {:evidence/intent
                                             {:intent/description "No sensitive data"}}))))))

(deftest ^{:stratum 0} compliance-metadata-keeps-secrets-separate-from-pii
  (testing "secret findings do not imply personal information"
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "Key AKIAABCDEFGHIJKLMNOP"}})]
      (is (= {:compliance/sensitive-findings [{:finding/type :aws-access-key}]}
             (scanner/compliance-metadata result))))))

(deftest ^{:stratum 0} scan-covers-the-streams-secret-set
  (testing "a secret the labelled patterns do not name is still reported"
    ;; N6.SD.3 requires the bundle to scan independently of the stream,
    ;; not to hold a narrower definition of "secret". A GitHub token
    ;; matches no pattern in this file, but the stream would redact it.
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "used ghp_abcdefghijklmnopqrstuvwxyz0123"}})]
      (is (= [{:finding/type :embedded-secret}] (:scan/findings result)))))

  (testing "a named secret is not also reported as an unnamed one"
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "Key AKIAABCDEFGHIJKLMNOP"}})]
      (is (= [{:finding/type :aws-access-key}] (:scan/findings result))
          "one secret, one finding")))

  (testing "PII alone is not a secret finding"
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "Contact alice@example.com"}})]
      (is (= [{:finding/type :email}] (:scan/findings result))))))

(deftest ^{:stratum 0} detection-is-bounded-but-redaction-is-not
  (testing "a secret too deep to scan is still redacted"
    ;; bundle-text is bounded by print-level, so detection sees a
    ;; truncated view. That makes findings best-effort metadata rather
    ;; than a security boundary — redaction walks the whole structure and
    ;; does not share the limit. Asserted here so the asymmetry stays a
    ;; documented property rather than an assumption.
    (let [deep (reduce (fn [acc _] {:n acc})
                       {:leaked "AKIAIOSFODNN7EXAMPLE"}
                       (range 30))]
      (is (empty? (:scan/findings (scanner/scan-artifact deep)))
          "the scan cannot see past its print-level bound")
      (is (not (str/includes?
                (binding [*print-level* nil *print-length* nil]
                  (pr-str (redaction/redact deep)))
                "AKIAIOSFODNN7EXAMPLE"))
          "redaction removes it regardless"))))
