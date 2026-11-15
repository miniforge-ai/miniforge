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
(ns ai.miniforge.codex.harvest-test
  "Harvest log text is inline, not a generator fixture: no codex generator
   emits the decided::/outcome::/misses:: fields yet, so these tests state
   the format the reader accepts."
  (:require [ai.miniforge.codex.interface :as codex]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} miss-a #uuid "11111111-1111-1111-1111-111111111111")
(def ^{:stratum 0} miss-b #uuid "22222222-2222-2222-2222-222222222222")

(def ^{:stratum 0} harvest-log
  (str "# Harvest 2026-09\n\n"
       "## contract drift admitted\n"
       "- decided:: 2026-09-04\n"
       "- outcome:: admitted\n"
       "- misses:: " miss-a ", not-a-uuid\n\n"
       "## liveness refused\n"
       "decided:: 2026-09-06\n"
       "outcome:: Refused\n"
       "misses:: " miss-b "\n\n"
       "## incomplete entry decides nothing\n"
       "decided:: 2026-09-07\n"
       "misses:: " miss-a "\n"))

(defn- ^{:stratum 0} temp-codex
  "A codex dir holding `files` ({relative-path text})."
  [files]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "codex-harvest-"
                      (into-array java.nio.file.attribute.FileAttribute [])))]
    (doseq [[path text] files]
      (io/make-parents (io/file dir path))
      (spit (io/file dir path) text))
    (str dir)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} harvest-sections-with-all-three-fields-are-decisions
  (let [dir (temp-codex {"harvest/HARVEST-2026-09.md" harvest-log
                         "nodes/problems/x.md"        "decided:: 2026-01-01\noutcome:: admitted\nmisses:: 33333333-3333-3333-3333-333333333333\n"})
        {:keys [decisions]} (codex/harvest-decisions dir)]
    (testing "only complete sections in HARVEST-*.md files decide misses"
      (is (= 2 (count decisions))))
    (testing "fields parse to a date, an outcome and miss ids; bad ids are dropped"
      (is (= {:decided (java.time.LocalDate/parse "2026-09-04")
              :outcome :admitted
              :misses  [miss-a]}
             (first decisions)))
      (is (= :refused (:outcome (second decisions)))))))

(deftest ^{:stratum 1} harvest-decisions-without-a-codex-is-an-anomaly
  (is (= :codex-unreadable
         (:codex/anomaly (codex/harvest-decisions "/nonexistent/codex/path")))))

(deftest ^{:stratum 1} a-codex-without-harvest-logs-has-no-decisions
  (is (= {:decisions []} (codex/harvest-decisions (temp-codex {"nodes/a.md" "# a\n"})))))

(deftest ^{:stratum 1} a-symlink-back-to-the-root-is-not-followed
  (let [dir (temp-codex {"HARVEST-2026-09.md" harvest-log})]
    (doseq [link ["loop-a" "loop-b"]]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file dir link)) (.toPath (io/file dir))
       (into-array java.nio.file.attribute.FileAttribute [])))
    (is (= 2 (count (:decisions (codex/harvest-decisions dir)))))))

