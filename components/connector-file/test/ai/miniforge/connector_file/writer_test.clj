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
(ns ai.miniforge.connector-file.writer-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-file.writer :as sut])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private read-eval-probe
  "System property that a #= form in a test file sets if it is evaluated."
  "ai.miniforge.connector-file.read-eval-probe")

(defn- ^{:stratum 0} tmp-edn-path
  "Path of an EDN file that does not exist yet, in a fresh temp directory.
   Both are removed when the JVM exits."
  []
  (let [dir  (.toFile (Files/createTempDirectory "connector-file-writer-test"
                                                 (make-array FileAttribute 0)))
        file (io/file dir "out.edn")]
    (.deleteOnExit dir)
    (.deleteOnExit file)
    (str file)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} write-edn-append-test
  (testing "append adds to the records already in the file"
    (let [path (tmp-edn-path)]
      (is (= 1 (sut/write-edn path [{:id 1}] :overwrite)))
      (is (= 1 (sut/write-edn path [{:id 2}] :append)))
      (is (= [{:id 1} {:id 2}] (edn/read-string (slurp path))))))
  (testing "append to a file that does not exist yet writes the records"
    (let [path (tmp-edn-path)]
      (is (= 2 (sut/write-edn path [{:id 1} {:id 2}] :append)))
      (is (= [{:id 1} {:id 2}] (edn/read-string (slurp path))))))
  (testing "append to an empty file writes the records"
    ;; clojure.edn/read-string returns nil at end of input, where
    ;; clojure.core/read-string threw, so an empty file reads as no records.
    (let [path (tmp-edn-path)]
      (spit path "")
      (is (= 1 (sut/write-edn path [{:id 1}] :append)))
      (is (= [{:id 1}] (edn/read-string (slurp path)))))))

(deftest ^{:stratum 1} write-edn-append-does-not-evaluate-test
  (testing "a #= form in the existing file is rejected, not run"
    (let [path    (tmp-edn-path)
          payload (str "[#=(java.lang.System/setProperty \"" read-eval-probe "\" \"evaluated\")]")]
      (spit path payload)
      (try
        (is (thrown? RuntimeException (sut/write-edn path [{:id 1}] :append)))
        (is (nil? (System/getProperty read-eval-probe)))
        (is (= payload (slurp path)))
        (finally
          (System/clearProperty read-eval-probe))))))
