(ns ai.miniforge.connector-sarif.impl-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [ai.miniforge.connector-sarif.impl :as impl]
            [ai.miniforge.connector-sarif.format :as fmt]))

;; --------------------------------------------------------------------------
;; Test helpers

(defn- create-temp-sarif-file
  "Create a temporary SARIF file with given results."
  [results]
  (let [f (java.io.File/createTempFile "test-scan" ".sarif")
        content {"$schema" "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"
                 "version" "2.1.0"
                 "runs" [{"tool" {"driver" {"name" "TestScanner" "version" "1.0"}}
                          "results" results}]}]
    (.deleteOnExit f)
    (spit f (json/generate-string content))
    (.getAbsolutePath f)))

(defn- create-sarif-violation
  "Create a SARIF result object for testing."
  [rule-id message & {:keys [level file line]
                      :or {level "warning" file "src/test.cpp" line 1}}]
  {"ruleId" rule-id
   "level" level
   "message" {"text" message}
   "locations" [{"physicalLocation"
                 {"artifactLocation" {"uri" file}
                  "region" {"startLine" line "startColumn" 1}}}]})

;; --------------------------------------------------------------------------
;; Tests

(deftest test-connect-and-close
  (testing "Connect with valid config"
    (let [temp-file (create-temp-sarif-file [])
          result    (impl/do-connect {:sarif/source-path temp-file})]
      (is (= :connected (:connector/status result)))
      (is (string? (:connection/handle result)))
      (impl/do-close (:connection/handle result))))

  (testing "Connect with invalid config throws"
    (is (thrown? Exception (impl/do-connect {})))))

(deftest test-discover
  (testing "Discover schemas from SARIF file"
    (let [temp-file (create-temp-sarif-file [(create-sarif-violation "API-001" "Test")])
          {:keys [connection/handle]} (impl/do-connect {:sarif/source-path temp-file})
          result (impl/do-discover handle {})]
      (is (seq (:schemas result)))
      (impl/do-close handle))))

(deftest test-extract
  (testing "Extract violations from SARIF"
    (let [temp-file (create-temp-sarif-file [(create-sarif-violation "API-001" "Undocumented call" :level "error")
                                             (create-sarif-violation "API-002" "Dynamic load" :level "warning")])
          {:keys [connection/handle]} (impl/do-connect {:sarif/source-path temp-file})
          result (impl/do-extract handle :sarif-result {})]
      (is (= 2 (count (:records result))))
      (is (= false (:extract/has-more result)))
      (let [v (first (:records result))]
        (is (= "API-001" (:violation/rule-id v)))
        (is (= :error (:violation/severity v)))
        (is (string? (:violation/message v))))
      (impl/do-close handle))))

(deftest test-format-detection
  (testing "Format auto-detection"
    (is (= :sarif (fmt/detect-format "scan.sarif")))
    (is (= :sarif (fmt/detect-format "scan.sarif.json")))
    (is (= :csv (fmt/detect-format "results.csv")))
    (is (= :unknown (fmt/detect-format "data.xml")))))

(deftest test-empty-file
  (testing "Empty SARIF file returns no violations"
    (let [temp-file (create-temp-sarif-file [])
          {:keys [connection/handle]} (impl/do-connect {:sarif/source-path temp-file})
          result (impl/do-extract handle :sarif-result {})]
      (is (empty? (:records result)))
      (impl/do-close handle))))
