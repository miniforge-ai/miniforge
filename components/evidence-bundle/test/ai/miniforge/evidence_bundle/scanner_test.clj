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

(deftest ^{:stratum 0} payment-card-scan-uses-shared-checksum-detector-test
  (let [value {:description "Synthetic test card 4111 1111 1111 1111"}
        findings (scanner/scan-artifact value)]
    (is (= [{:finding/type :payment-card}] (:scan/findings findings)))
    (is (true? (:evidence/contains-pii? (scanner/compliance-metadata findings))))
    (is (= {:description "Synthetic test card [REDACTED]"} (redaction/redact value))))
  (is (empty? (:scan/findings (scanner/scan-artifact {:description "4111111111111112"})))))

(deftest ^{:stratum 0} card-findings-do-not-cross-original-value-boundaries-test
  (doseq [value [{:card 4111111111111111} {:card 4111111111111111N}]]
    (is (= [{:finding/type :payment-card}] (:scan/findings (scanner/scan-artifact value))))
    (is (= [{:finding/type :redaction-marker}]
           (:scan/findings (scanner/scan-artifact (redaction/redact value))))))
  (let [separate {:measurements [4111 1111 1111 1111]}]
    (is (empty? (:scan/findings (scanner/scan-artifact separate))))
    (is (= separate (redaction/redact separate)))))

(deftest ^{:stratum 0} metadata-only-secrets-match-the-shared-redaction-contract
  (doseq [value [(with-meta [] {:note "AKIAIOSFODNN7EXAMPLE"})
                {:nested [(with-meta [] {:note "AKIAIOSFODNN7EXAMPLE"})]}
                {(with-meta 'key {:note "AKIAIOSFODNN7EXAMPLE"}) :value}
                (with-meta [] (with-meta {} {:note "AKIAIOSFODNN7EXAMPLE"}))]]
    (let [redacted (redaction/redact value)]
      (is (false? (redaction/clean? value)))
      (is (= [{:finding/type :aws-access-key}] (:scan/findings (scanner/scan-artifact value))))
      (is (redaction/clean? redacted))
      (is (= [{:finding/type :redaction-marker}] (:scan/findings (scanner/scan-artifact redacted)))))))

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

(deftest ^{:stratum 0} shared-detection-and-redaction-ignore-print-limits
  (testing "named findings and redaction both inspect the original value"
    (let [deep (reduce (fn [acc _] {:n acc})
                       {:leaked "AKIAIOSFODNN7EXAMPLE"}
                       (range 30))]
      (is (= [{:finding/type :aws-access-key}]
             (:scan/findings (scanner/scan-artifact deep)))
          "named patterns see past print bounds")
      (is (not (str/includes?
                (binding [*print-level* nil *print-length* nil]
                  (pr-str (redaction/redact deep)))
                "AKIAIOSFODNN7EXAMPLE"))
          "redaction removes it regardless"))))
