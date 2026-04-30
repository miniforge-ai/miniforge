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

(ns ai.miniforge.connector-sarif.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.connector-sarif.schema :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- valid-violation
  [& {:as overrides}]
  (merge {:violation/id          "v-1"
          :violation/rule-id     "R-1"
          :violation/message     "m"
          :violation/severity    :warning
          :violation/location    {:file "src/a.clj" :line 10 :column 5}
          :violation/source-tool "TestTool"
          :violation/raw         {}}
         overrides))

(defn- valid-config
  [& {:as overrides}]
  ;; The schema validates only that :sarif/source-path is a string. Use the
  ;; platform-neutral java.io.tmpdir rather than a hard-coded Unix path so
  ;; the test isn't a Unix-only fixture by accident.
  (merge {:sarif/source-path (System/getProperty "java.io.tmpdir")} overrides))

;------------------------------------------------------------------------------ Layer 1
;; Enum exposure

(deftest enums-are-stable-test
  (testing "Each enum schema declares the documented closed set"
    (is (= [:enum :error :warning :note :none] sut/SeverityLevel))
    (is (= [:enum :sarif-result :sarif-run :csv-violation] sut/SchemaType))
    (is (= [:enum :sarif :csv :auto] sut/SourceFormat))))

;------------------------------------------------------------------------------ Layer 1
;; validate / validate-violation

(deftest validate-violation-valid-test
  (testing "A fully-populated violation validates"
    (let [{:keys [valid? errors]} (sut/validate-violation (valid-violation))]
      (is (true? valid?))
      (is (nil? errors)))))

(deftest validate-violation-rejects-unknown-severity-test
  (testing "Severity outside the enum fails validation"
    (let [{:keys [valid? errors]} (sut/validate-violation
                                   (valid-violation :violation/severity :critical))]
      (is (false? valid?))
      (is (some? errors)))))

(deftest validate-violation-rejects-missing-required-test
  (testing "Missing :violation/rule-id fails validation"
    (let [{:keys [valid?]} (sut/validate-violation
                            (dissoc (valid-violation) :violation/rule-id))]
      (is (false? valid?)))))

(deftest validate-violation-allows-empty-location-test
  (testing "All location fields are optional and may be missing or nil"
    (is (true? (:valid? (sut/validate-violation
                         (valid-violation :violation/location {})))))
    (is (true? (:valid? (sut/validate-violation
                         (valid-violation :violation/location {:file "f"})))))))

;------------------------------------------------------------------------------ Layer 1
;; validate / validate-config

(deftest validate-config-minimal-test
  (testing "Only :sarif/source-path is required"
    (is (true? (:valid? (sut/validate-config (valid-config)))))))

(deftest validate-config-rejects-empty-test
  (testing "Empty config (no :sarif/source-path) fails validation"
    (is (false? (:valid? (sut/validate-config {})))))
  (testing "Non-string source-path fails validation"
    (is (false? (:valid? (sut/validate-config {:sarif/source-path 42}))))))

(deftest validate-config-format-enum-test
  (testing ":sarif/format must be :sarif, :csv, or :auto when supplied"
    (is (true?  (:valid? (sut/validate-config (valid-config :sarif/format :sarif)))))
    (is (true?  (:valid? (sut/validate-config (valid-config :sarif/format :csv)))))
    (is (true?  (:valid? (sut/validate-config (valid-config :sarif/format :auto)))))
    (is (false? (:valid? (sut/validate-config (valid-config :sarif/format :json)))))))

(deftest validate-config-csv-columns-shape-test
  (testing ":sarif/csv-columns must be keyword → string when supplied"
    (is (true?  (:valid? (sut/validate-config
                          (valid-config :sarif/csv-columns {:rule-id "Rule" :file "Path"})))))
    (is (false? (:valid? (sut/validate-config
                          (valid-config :sarif/csv-columns {"rule-id" "Rule"})))))))

;------------------------------------------------------------------------------ Layer 1
;; JSON Schema export

(deftest json-schema-export-shape-test
  (testing "violation-json-schema returns a JSON Schema map declaring the violation type"
    (let [s (sut/violation-json-schema)]
      (is (map? s))
      ;; malli's json-schema transform yields :type "object" for [:map ...]
      (is (= "object" (:type s)))
      (is (contains? (:properties s) :violation/id))
      (is (contains? (:properties s) :violation/severity)))))

(deftest config-json-schema-export-shape-test
  (testing "config-json-schema returns a JSON Schema map for the connector config"
    (let [s (sut/config-json-schema)]
      (is (map? s))
      (is (= "object" (:type s)))
      (is (contains? (:properties s) :sarif/source-path)))))
