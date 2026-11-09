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
(ns ai.miniforge.qualification.build-writer-test
  "Build identity never reports an unreadable build as clean; the writer
   round-trips a report as EDN and JSON."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.qualification.build :as build]
            [ai.miniforge.qualification.writer :as writer]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(defn- ^{:stratum 0} temp-dir []
  (str (Files/createTempDirectory "qualification-test" (make-array FileAttribute 0))))

(def ^{:stratum 0} ^:private report
  {:report/gate-id    :fixture/gate
   :report/started-at "2026-11-08T12:00:00Z"
   :report/build      {:build/git-sha (apply str (repeat 40 "a"))
                       :build/clean?  true}
   :report/verdict    :verdict/pass})

(deftest ^{:stratum 0} test-workspace-commit-is-a-full-sha
  (testing "given the workspace root → a 40-character commit, or, outside a checkout, none and not clean"
    (if-let [sha (build/git-sha ".")]
      (is (re-matches #"[0-9a-f]{40}" sha))
      (is (false? (build/clean? "."))))))

(def ^{:stratum 0} ^:private repository-env-vars
  "Variables that bind git to another repository; with any set, `git init`
   in a temp directory could act on that repository instead."
  ["GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_COMMON_DIR" "GIT_OBJECT_DIRECTORY"])

;------------------------------------------------------------------------------ Layer 1

;; Tests
(deftest ^{:stratum 1} test-directory-outside-a-repository
  (testing "given a directory git cannot read → no commit, and not clean"
    (let [dir (temp-dir)]
      (is (nil? (build/git-sha dir)))
      (is (false? (build/clean? dir))))))

(deftest ^{:stratum 1} test-repository-without-commit-is-not-clean
  (testing "given an initialized repository with no commit → no commit, and not clean"
    (when (not-any? #(System/getenv %) repository-env-vars)
      (let [dir (temp-dir)]
        (shell/sh "git" "init" "-q" :dir dir)
        (is (= {:build/git-sha nil :build/clean? false}
               (select-keys (build/build-identity dir) [:build/git-sha :build/clean?])))))))

(deftest ^{:stratum 1} test-deps-hash-follows-dependency-files
  (testing "given the same files → the same hash; a changed deps.edn → a different hash"
    (let [a (temp-dir) b (temp-dir)]
      (spit (io/file a "deps.edn") "{:deps {}}")
      (spit (io/file b "deps.edn") "{:deps {}}")
      (is (= (build/deps-hash a) (build/deps-hash b)))
      (spit (io/file b "deps.edn") "{:deps {x/y {:mvn/version \"1\"}}}")
      (is (not= (build/deps-hash a) (build/deps-hash b))))))

(deftest ^{:stratum 1} test-report-paths-name-gate-and-run
  (testing "given a report → one directory per gate, a file pair per run"
    (let [{:keys [edn json]} (writer/report-paths "out" report)]
      (is (= "out/fixture.gate/2026-11-08T12-00-00Z-aaaaaaaaaaaa.edn" edn))
      (is (= "out/fixture.gate/2026-11-08T12-00-00Z-aaaaaaaaaaaa.json" json))))
  (testing "given a commit shorter than 12 characters → the whole commit, no exception"
    (is (re-find #"-abc\.edn$"
                 (:edn (writer/report-paths "out" (assoc-in report [:report/build :build/git-sha] "abc"))))))
  (testing "given no commit → the stem says so"
    (is (re-find #"no-commit\.edn$"
                 (:edn (writer/report-paths "out" (assoc-in report [:report/build :build/git-sha] nil)))))))

(deftest ^{:stratum 1} test-report-paths-stay-in-the-output-directory
  (testing "given a gate id and a commit holding path separators and .. → both files beneath the output directory"
    (let [dir    (temp-dir)
          hostile (-> report
                      (assoc :report/gate-id (keyword "../.." "escape"))
                      (assoc-in [:report/build :build/git-sha] "../../x"))
          base   (.getCanonicalPath (io/file dir))
          {:keys [edn json]} (writer/write-report! dir hostile)]
      (is (.startsWith (.getCanonicalPath (io/file edn)) (str base "/")))
      (is (.startsWith (.getCanonicalPath (io/file json)) (str base "/"))))))

(deftest ^{:stratum 1} test-writer-refuses-escapes-and-merged-keys
  (testing "given a gate directory that is a symlink outside, or two keys one JSON property → throws, nothing written"
    (let [dir     (temp-dir)
          outside (temp-dir)]
      (Files/createSymbolicLink (.toPath (io/file dir "fixture.gate")) (.toPath (io/file outside))
                                (make-array FileAttribute 0))
      (is (thrown? Exception (writer/write-report! dir report)))
      (is (empty? (.list (io/file outside)))))
    (let [dir (temp-dir)]
      (is (thrown? Exception (writer/write-report! dir (assoc report :report/extra {:sink/calls 0 "sink/calls" 1}))))
      (is (empty? (.list (io/file dir)))))))

(deftest ^{:stratum 1} test-written-report-round-trips
  (testing "given a report → the EDN reads back equal, the JSON keeps namespaced names"
    (let [{:keys [edn json]} (writer/write-report! (temp-dir) report)]
      (is (= report (edn/read-string (slurp edn))))
      (is (= "fixture/gate" (get (json/parse-string (slurp json)) "report/gate-id")))
      (is (= "verdict/pass" (get (json/parse-string (slurp json)) "report/verdict"))))))
