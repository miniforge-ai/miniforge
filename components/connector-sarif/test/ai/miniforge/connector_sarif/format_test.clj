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

(ns ai.miniforge.connector-sarif.format-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [ai.miniforge.connector-sarif.format :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- temp-file!
  "Create a temp file with given content and `extension`. Returns absolute path."
  [extension content]
  (let [f (java.io.File/createTempFile "sarif-test" extension)]
    (.deleteOnExit f)
    (spit f content)
    (.getAbsolutePath f)))

(defn- delete-recursively!
  "Recursively delete `f`. JVM File/deleteOnExit cannot remove non-empty
   directories, so register an explicit shutdown hook for each temp dir."
  [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-recursively! child)))
    (.delete f)))

(defn- temp-dir!
  "Create a temp directory and register a shutdown hook to delete it
   recursively on JVM exit. Returns the File."
  [prefix]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      prefix
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(delete-recursively! dir)))
    dir))

(defn- sarif-result
  "Build a SARIF result object for tests."
  [rule-id message & {:keys [level file line column]
                      :or {level "warning" file "src/x.cpp" line 10 column 5}}]
  {"ruleId"    rule-id
   "level"     level
   "message"   {"text" message}
   "locations" [{"physicalLocation"
                 {"artifactLocation" {"uri" file}
                  "region" {"startLine" line "startColumn" column}}}]})

(defn- sarif-doc
  "Build a complete SARIF JSON document with one or more runs."
  ([results] (sarif-doc "TestScanner" results))
  ([tool-name results]
   {"$schema" "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"
    "version" "2.1.0"
    "runs"    [{"tool" {"driver" {"name" tool-name "version" "1.0"}}
                "results" results}]}))

(defn- sarif-multi-run-doc
  "Build a SARIF document with multiple runs."
  [run-specs]
  {"version" "2.1.0"
   "runs" (mapv (fn [{:keys [tool results]}]
                  {"tool" {"driver" {"name" tool}}
                   "results" results})
                run-specs)})

(defn- temp-sarif!
  ([results] (temp-sarif! "TestScanner" results))
  ([tool-name results]
   (temp-file! ".sarif" (json/generate-string (sarif-doc tool-name results)))))

(defn- temp-csv!
  "Build a CSV temp file from a vector of header strings and a vector of row vectors."
  [headers rows]
  (let [header-line (clojure.string/join "," headers)
        body (when (seq rows)
               (str "\n"
                    (clojure.string/join "\n"
                                         (map #(clojure.string/join "," %) rows))))
        content (str header-line body "\n")]
    (temp-file! ".csv" content)))

;------------------------------------------------------------------------------ Layer 1
;; detect-format

(deftest detect-format-known-extensions-test
  (testing ".sarif and .sarif.json detect as :sarif"
    (is (= :sarif (sut/detect-format "scan.sarif")))
    (is (= :sarif (sut/detect-format "scan.sarif.json")))
    (is (= :sarif (sut/detect-format "/abs/path/scan.SARIF")))) ;; case-insensitive
  (testing ".csv detects as :csv"
    (is (= :csv (sut/detect-format "results.csv")))
    (is (= :csv (sut/detect-format "Results.CSV"))))
  (testing "Unknown extensions detect as :unknown"
    (is (= :unknown (sut/detect-format "data.xml")))
    (is (= :unknown (sut/detect-format "no-extension")))
    (is (= :unknown (sut/detect-format "")))))

;------------------------------------------------------------------------------ Layer 1
;; parse-sarif — happy paths and edge cases

(deftest parse-sarif-empty-runs-test
  (testing "SARIF document with no runs yields an empty vector"
    (let [path (temp-file! ".sarif" (json/generate-string {"version" "2.1.0" "runs" []}))]
      (is (= [] (sut/parse-sarif path))))))

(deftest parse-sarif-empty-results-test
  (testing "Run with no results yields an empty vector"
    (is (= [] (sut/parse-sarif (temp-sarif! []))))))

(deftest parse-sarif-single-result-shape-test
  (testing "Single result is parsed into a fully-populated violation map"
    (let [path (temp-sarif!
                "ToolX"
                [(sarif-result "RULE-1" "Bad practice"
                               :level "error" :file "src/a.clj" :line 42 :column 7)])
          [v] (sut/parse-sarif path)]
      (is (= "ToolX:0:0"        (:violation/id v)))
      (is (= "RULE-1"           (:violation/rule-id v)))
      (is (= "Bad practice"     (:violation/message v)))
      (is (= :error             (:violation/severity v)))
      (is (= "ToolX"            (:violation/source-tool v)))
      (is (= {:file "src/a.clj" :line 42 :column 7}
             (:violation/location v)))
      (is (map? (:violation/raw v))))))

(deftest parse-sarif-severity-normalization-test
  (testing "All SARIF level strings normalize to expected severity keywords"
    (let [path (temp-sarif!
                [(sarif-result "R" "m" :level "error")
                 (sarif-result "R" "m" :level "warning")
                 (sarif-result "R" "m" :level "note")
                 (sarif-result "R" "m" :level "none")
                 (sarif-result "R" "m" :level "WARNING")            ;; case
                 (sarif-result "R" "m" :level "weirdness")          ;; unknown ⇒ :warning
                 (-> (sarif-result "R" "m") (dissoc "level"))])     ;; missing ⇒ :warning
          severities (mapv :violation/severity (sut/parse-sarif path))]
      (is (= [:error :warning :note :none :warning :warning :warning] severities)))))

(deftest parse-sarif-missing-message-or-location-test
  (testing "Missing message text defaults to empty string; missing locations
            yield an empty location map"
    (let [path (temp-file!
                ".sarif"
                (json/generate-string
                 {"version" "2.1.0"
                  "runs" [{"tool" {"driver" {"name" "T"}}
                           "results" [{"ruleId" "R"}]}]}))
          [v] (sut/parse-sarif path)]
      (is (= ""  (:violation/message v)))
      (is (= {} (:violation/location v))))))

(deftest parse-sarif-missing-tool-name-test
  (testing "Missing tool driver name falls back to 'unknown'"
    (let [path (temp-file!
                ".sarif"
                (json/generate-string
                 {"version" "2.1.0"
                  "runs" [{"tool" {} "results" [{"ruleId" "R"}]}]}))
          [v] (sut/parse-sarif path)]
      (is (= "unknown:0:0" (:violation/id v)))
      (is (= "unknown"     (:violation/source-tool v))))))

(deftest parse-sarif-multi-run-id-format-test
  (testing "IDs encode tool-name:run-idx:result-idx across multiple runs"
    (let [path (temp-file!
                ".sarif"
                (json/generate-string
                 (sarif-multi-run-doc
                  [{:tool "ToolA"
                    :results [(sarif-result "A1" "m1") (sarif-result "A2" "m2")]}
                   {:tool "ToolB"
                    :results [(sarif-result "B1" "m1")]}])))
          ids (mapv :violation/id (sut/parse-sarif path))]
      (is (= ["ToolA:0:0" "ToolA:0:1" "ToolB:1:0"] ids)))))

(deftest parse-sarif-missing-rule-id-defaults-to-unknown-test
  (testing "Result without ruleId gets :violation/rule-id 'unknown'"
    (let [path (temp-file!
                ".sarif"
                (json/generate-string
                 {"version" "2.1.0"
                  "runs" [{"tool" {"driver" {"name" "T"}}
                           "results" [{"message" {"text" "no rule"}}]}]}))
          [v] (sut/parse-sarif path)]
      (is (= "unknown" (:violation/rule-id v))))))

;------------------------------------------------------------------------------ Layer 1
;; parse-csv — default columns + custom mapping

(deftest parse-csv-default-columns-test
  (testing "CSV with default column headers maps to violation records"
    (let [path (temp-csv! ["Rule ID" "Message" "Severity" "File" "Line" "Column"]
                          [["R-1" "Issue one" "error"   "src/a.clj" "1" "5"]
                           ["R-2" "Issue two" "warning" "src/b.clj" "2" "3"]])
          [v1 v2] (sut/parse-csv path)]
      (is (= "csv:0"             (:violation/id v1)))
      (is (= "R-1"               (:violation/rule-id v1)))
      (is (= "Issue one"         (:violation/message v1)))
      (is (= :error              (:violation/severity v1)))
      (is (= "src/a.clj"         (-> v1 :violation/location :file)))
      (is (= 1                   (-> v1 :violation/location :line)))
      (is (= 5                   (-> v1 :violation/location :column)))
      (is (= "csv-import"        (:violation/source-tool v1)))
      (is (= "csv:1"             (:violation/id v2)))
      (is (= :warning            (:violation/severity v2))))))

(deftest parse-csv-case-insensitive-headers-test
  (testing "Header lookup is case-insensitive and trims whitespace"
    (let [path (temp-csv! ["RULE ID" " message " "severity" "FILE" "line" "column"]
                          [["X" "msg" "note" "f.clj" "9" "1"]])
          [v] (sut/parse-csv path)]
      (is (= "X"     (:violation/rule-id v)))
      (is (= "msg"   (:violation/message v)))
      (is (= :note   (:violation/severity v)))
      (is (= "f.clj" (-> v :violation/location :file))))))

(deftest parse-csv-non-numeric-line-column-test
  (testing "Non-numeric line/column values become nil rather than throwing"
    (let [path (temp-csv! ["Rule ID" "Message" "Severity" "File" "Line" "Column"]
                          [["R" "m" "error" "f.clj" "n/a" "x"]])
          [v] (sut/parse-csv path)]
      (is (nil? (-> v :violation/location :line)))
      (is (nil? (-> v :violation/location :column))))))

(deftest parse-csv-custom-column-mapping-test
  (testing "Custom column mapping is merged on top of defaults"
    (let [path (temp-csv! ["TheRule" "TheMessage" "TheSeverity" "ThePath" "TheLine" "TheCol"]
                          [["R" "m" "error" "f.clj" "1" "1"]])
          [v] (sut/parse-csv path
                             {:rule-id  "TheRule"
                              :message  "TheMessage"
                              :severity "TheSeverity"
                              :file     "ThePath"
                              :line     "TheLine"
                              :column   "TheCol"})]
      (is (= "R"     (:violation/rule-id v)))
      (is (= "m"     (:violation/message v)))
      (is (= :error  (:violation/severity v)))
      (is (= "f.clj" (-> v :violation/location :file))))))

(deftest parse-csv-empty-file-test
  (testing "CSV with only a header row yields no violations"
    (let [path (temp-csv! ["Rule ID" "Message" "Severity" "File" "Line" "Column"] [])]
      (is (= [] (sut/parse-csv path))))))

(deftest parse-csv-missing-columns-defaults-test
  (testing "Missing/unmappable columns yield 'unknown' rule-id and empty message"
    (let [path (temp-csv! ["Other"] [["just-a-value"]])
          [v] (sut/parse-csv path)]
      (is (= "unknown" (:violation/rule-id v)))
      (is (= ""        (:violation/message v))))))

;------------------------------------------------------------------------------ Layer 1
;; list-scan-files

(deftest list-scan-files-nonexistent-path-test
  (testing "Path that doesn't exist yields an empty vector"
    (is (= [] (sut/list-scan-files "/totally/not/here.sarif")))))

(deftest list-scan-files-single-file-test
  (testing "A single existing file is returned in a vector with its absolute path"
    (let [path (temp-sarif! [])]
      (is (= [path] (sut/list-scan-files path))))))

(deftest list-scan-files-directory-filters-by-extension-test
  (testing "A directory yields only .sarif and .csv files; other extensions skipped"
    (let [tmp (temp-dir! "sarif-list-test")
          sarif-f (io/file tmp "scan.sarif")
          csv-f   (io/file tmp "results.csv")
          xml-f   (io/file tmp "data.xml")]
      (spit sarif-f "{}")
      (spit csv-f  "Rule ID\n")
      (spit xml-f  "<x/>")
      (let [files (sut/list-scan-files (.getAbsolutePath tmp))]
        (is (= 2 (count files)))
        (is (some #(.endsWith ^String % "scan.sarif") files))
        (is (some #(.endsWith ^String % "results.csv") files))
        (is (not (some #(.endsWith ^String % "data.xml") files)))))))

(deftest list-scan-files-empty-directory-test
  (testing "Empty directory yields an empty vector"
    (let [tmp (temp-dir! "sarif-list-empty")]
      (is (= [] (sut/list-scan-files (.getAbsolutePath tmp)))))))

;------------------------------------------------------------------------------ Layer 1
;; parse-file dispatch

(deftest parse-file-dispatches-by-format-test
  (testing "Explicit :sarif format parses via parse-sarif"
    (let [path (temp-sarif! [(sarif-result "R" "m")])]
      (is (= 1 (count (sut/parse-file path :sarif nil))))))
  (testing "Explicit :csv format parses via parse-csv"
    (let [path (temp-csv! ["Rule ID" "Message" "Severity" "File" "Line" "Column"]
                          [["R" "m" "error" "f" "1" "1"]])]
      (is (= 1 (count (sut/parse-file path :csv nil)))))))

(deftest parse-file-auto-detects-format-test
  (testing ":auto and nil format both detect by extension"
    (let [sarif-path (temp-sarif! [(sarif-result "R" "m")])
          csv-path   (temp-csv! ["Rule ID" "Message" "Severity" "File" "Line" "Column"]
                                [["R" "m" "error" "f" "1" "1"]])]
      (is (= 1 (count (sut/parse-file sarif-path :auto nil))))
      (is (= 1 (count (sut/parse-file csv-path nil nil)))))))

(deftest parse-file-unsupported-format-throws-test
  (testing "Unknown format throws ex-info with :path and :format keys"
    (let [unknown-path (temp-file! ".xml" "<x/>")
          thrown (try
                   (sut/parse-file unknown-path nil nil)
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (let [data (ex-data thrown)]
        (is (= unknown-path (:path data)))
        (is (= :unknown     (:format data)))))))
