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
(ns ai.miniforge.llm.stream-capture-limit-test
  "The agent CLI's stdout and stderr readers keep at most the subprocess
   capture limit, and a cut stream says so."
  (:require
   [ai.miniforge.llm.protocols.impl.llm-client.subprocess :as sut]
   [ai.miniforge.subprocess.interface :as subprocess]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io ByteArrayInputStream)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private limit-bytes
  "A capture limit the input below passes four times over."
  16)

(def ^{:stratum 0} ^:private join-wait-ms
  "How long the reader thread gets over an in-memory stream, which it
   finishes in microseconds; reaching this means it hung."
  5000)

(def ^{:stratum 0} ^:private bounded-read
  "The component's read, captured before the test redefines its var."
  subprocess/read-bounded)

(defn- ^{:stratum 0} x-bytes
  [n]
  (.getBytes (apply str (repeat n \x)) "UTF-8"))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} small-limit-read
  [stream]
  (bounded-read stream {:max-bytes limit-bytes}))

(deftest ^{:stratum 1} read-process-stream-short-input-test
  (testing "a stream under the default limit arrives whole, with no note"
    (let [output (atom "")
          input  (x-bytes limit-bytes)
          reader (sut/read-process-stream! "capture-limit-test-reader"
                                           (ByteArrayInputStream. input)
                                           output)]
      (.join ^Thread reader join-wait-ms)
      (is (= (String. ^bytes input "UTF-8") @output)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} read-process-stream-keeps-at-most-the-limit-test
  (testing "a stream past the limit leaves its head and the cut note in the atom"
    (with-redefs [subprocess/read-bounded small-limit-read]
      (let [output (atom "")
            reader (sut/read-process-stream! "capture-limit-test-reader"
                                             (ByteArrayInputStream. (x-bytes (* 4 limit-bytes)))
                                             output)]
        (.join ^Thread reader join-wait-ms)
        (let [[kept note] (map #(apply str %) (split-at limit-bytes @output))]
          (is (= (apply str (repeat limit-bytes \x)) kept))
          (is (str/includes? note (str limit-bytes))
              "the text after the kept head is the cut note, naming the limit"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.stream-capture-limit-test)
  :leave-this-here)
