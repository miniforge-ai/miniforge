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
(ns ai.miniforge.deliberation-harness.journal-test
  "Integration tests: the journal's contract is durable files, so these run
   against a temporary directory."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.journal :as journal]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-parent []
  (.toFile (Files/createTempDirectory "deliberation-journal-" (make-array FileAttribute 0))))

(defn- ^{:stratum 0} delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (io/delete-file file true)))

(defn- ^{:stratum 0} with-journal-dir [f]
  (let [parent (temp-parent)]
    (try (f (str (io/file parent "run")))
         (finally (delete-tree! parent)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} records-round-trip-as-one-chain
  (with-journal-dir
    (fn [dir]
      (let [log (journal/open! dir)
            hashes [(journal/append! log {:kind :opened})
                    (journal/append! log {:kind :closed})]
            records (journal/read-journal dir)]
        (is (= [{:kind :opened} {:kind :closed}] (mapv :payload records)))
        (is (= hashes (mapv :hash records)))
        (is (= (first hashes) (:previous (second records))))))))

(deftest ^{:stratum 1 :integration true} a-run-never-reuses-a-directory
  (with-journal-dir
    (fn [dir]
      (journal/open! dir)
      (is (anomaly/anomaly? (journal/open! dir))))))

(deftest ^{:stratum 1 :integration true} tampering-breaks-the-chain
  (with-journal-dir
    (fn [dir]
      (let [log (journal/open! dir)]
        (journal/append! log {:turn 1})
        (journal/append! log {:turn 2})
        (spit (io/file dir "000001.edn") (pr-str {:sequence 1 :payload {:turn 9000}}))
        (testing "an edited record is detected"
          (is (anomaly/anomaly? (journal/read-journal dir))))))))

(deftest ^{:stratum 1 :integration true} an-unlistable-directory-is-an-anomaly
  (with-journal-dir
    (fn [dir]
      (is (anomaly/anomaly? (journal/read-journal dir))))))

(deftest ^{:stratum 1 :integration true} an-unreadable-record-is-an-anomaly
  (with-journal-dir
    (fn [dir]
      (journal/open! dir)
      (.mkdir (io/file dir "000000.edn"))
      (let [result (journal/read-journal dir)]
        (is (anomaly/anomaly? result))
        (is (= :unavailable (:anomaly/type result)))))))

(deftest ^{:stratum 1 :integration true} record-files-sort-numerically-past-six-digits
  (with-journal-dir
    (fn [dir]
      (journal/open! dir)
      (doseq [name ["1000000.edn" "999999.edn" "notes.txt"]]
        (spit (io/file dir name) "{}"))
      (is (= ["999999.edn" "1000000.edn"]
             (map #(.getName ^java.io.File %) (#'journal/record-files dir)))))))

(deftest ^{:stratum 1 :integration true} a-payload-that-cannot-replay-is-refused
  (with-journal-dir
    (fn [dir]
      (let [log (journal/open! dir)]
        (is (anomaly/anomaly? (journal/append! log {:at (java.time.Instant/now)})))
        (testing "nothing was written and the position did not move"
          (is (= [] (journal/read-journal dir)))
          (journal/append! log {:turn 1})
          (is (= [0] (mapv :sequence (journal/read-journal dir)))))))))

(deftest ^{:stratum 1 :integration true} a-malformed-record-names-its-file
  (with-journal-dir
    (fn [dir]
      (journal/open! dir)
      (spit (io/file dir "000000.edn") "{:sequence")
      (is (some? (get-in (journal/read-journal dir) [:anomaly/data :source]))))))

(deftest ^{:stratum 1 :integration true} anomaly-shaped-record-data-breaks-the-chain
  (with-journal-dir
    (fn [dir]
      (journal/open! dir)
      (spit (io/file dir "000000.edn")
            (str "{:anomaly/type :fault :anomaly/message \"m\" :anomaly/data {}"
                 " :anomaly/at #inst \"2026-01-01T00:00:00.000-00:00\"}"))
      (let [result (journal/read-journal dir)]
        (is (= :conflict (:anomaly/type result)))
        (is (some? (get-in result [:anomaly/data :file])))))))

(deftest ^{:stratum 1 :integration true} a-misnumbered-record-file-breaks-the-chain
  (with-journal-dir
    (fn [dir]
      (let [log (journal/open! dir)]
        (journal/append! log {:turn 1})
        (.renameTo (io/file dir "000000.edn") (io/file dir "0000000.edn"))
        (let [result (journal/read-journal dir)]
          (is (= :conflict (:anomaly/type result)))
          (is (some? (get-in result [:anomaly/data :file]))))))))
