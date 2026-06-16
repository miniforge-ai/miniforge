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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.scanner :as scanner]))

(deftest scan-artifact-reports-finding-types-only
  (testing "sensitive values are detected but not copied into evidence"
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "Contact alice@example.com"}})
          metadata (scanner/compliance-metadata result)]
      (is (= [{:finding/type :email}] (:scan/findings result)))
      (is (= {:evidence/contains-pii? true
              :compliance/sensitive-findings [{:finding/type :email}]}
             metadata)))))

(deftest compliance-metadata-is-empty-without-findings
  (testing "absence of findings does not overwrite caller-provided compliance flags"
    (is (= {} (scanner/compliance-metadata (scanner/scan-artifact
                                            {:evidence/intent
                                             {:intent/description "No sensitive data"}}))))))

(deftest compliance-metadata-keeps-secrets-separate-from-pii
  (testing "secret findings do not imply personal information"
    (let [result (scanner/scan-artifact
                  {:evidence/intent
                   {:intent/description "Key AKIAABCDEFGHIJKLMNOP"}})]
      (is (= {:compliance/sensitive-findings [{:finding/type :aws-access-key}]}
             (scanner/compliance-metadata result))))))
