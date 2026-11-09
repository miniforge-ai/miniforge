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
(ns ai.miniforge.qualification.interface-test
  "run-gate! writes a valid report for every scenario outcome, and only a
   run where every criterion passed exits 0."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.qualification.interface :as qualification]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private gate
  {:gate/id       :fixture/gate
   :gate/title    "Fixture gate"
   :gate/issue    1
   :gate/criteria [{:criterion/id :c/one :criterion/description "First criterion"}
                   {:criterion/id :c/two :criterion/description "Second criterion"}]})

(def ^{:stratum 0} ^:private command ["bb" "qualify" "fixture/gate"])

(def ^{:stratum 0} ^:private clean-build
  {:build/git-sha   (apply str (repeat 40 "a"))
   :build/clean?    true
   :build/deps-hash (apply str (repeat 64 "b"))})

(defn- ^{:stratum 0} temp-dir []
  (str (Files/createTempDirectory "qualification-run" (make-array FileAttribute 0))))

(defn- ^{:stratum 0} scenario-returning
  "A scenario that reports `results` over fixed fixtures and config."
  [results]
  (fn [] {:scenario/fixtures {:records [{:id 1}]}
          :scenario/config   {:pipeline :fixture}
          :scenario/results  results}))

(def ^{:stratum 0} ^:private all-pass
  [{:criterion/id :c/one :criterion/status :status/pass :criterion/observed {:sink-calls 0}}
   {:criterion/id :c/two :criterion/status :status/pass}])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-in-temp
  "Run `scenario` with output in a fresh directory, on `build` (default a
   clean, identified commit)."
  ([scenario] (run-in-temp scenario clean-build))
  ([scenario build]
   (qualification/run-gate! gate scenario {:out-dir  (temp-dir)
                                           :command  command
                                           :build-fn (constantly build)})))

(deftest ^{:stratum 1} test-run-without-a-command-writes-nothing
  (testing "given no repeat command → no files written, exit 2, even when every criterion passed"
    (let [dir    (temp-dir)
          result (qualification/run-gate! gate (scenario-returning all-pass)
                                          {:out-dir dir :build-fn (constantly clean-build)})]
      (is (string? (:invalid-report result)))
      (is (nil? (:paths result)))
      (is (empty? (.list (io/file dir))))
      (is (= 2 (qualification/exit-code result)))
      (is (= 1 (count (qualification/summary-lines result)))))))

(deftest ^{:stratum 1} test-invalid-gate-is-refused
  (testing "given a gate without criteria → no report, exit 2"
    (let [result (qualification/run-gate! (assoc gate :gate/criteria [])
                                          (scenario-returning all-pass)
                                          {:out-dir (temp-dir) :command command})]
      (is (nil? (:report result)))
      (is (= 2 (qualification/exit-code result)))
      (is (= 1 (count (qualification/summary-lines result)))))))

(deftest ^{:stratum 1} test-unwritable-report-fails-the-run
  (testing "given an output directory that is a file → write failure reported, exit 1 even when every criterion passed"
    (let [blocker (io/file (temp-dir) "not-a-directory")
          _       (spit blocker "")
          result  (qualification/run-gate! gate (scenario-returning all-pass)
                                           {:out-dir  (str blocker)
                                            :command  command
                                            :build-fn (constantly clean-build)})]
      (is (string? (:write-failure result)))
      (is (= :verdict/pass (get-in result [:report :report/verdict])))
      (is (= 1 (qualification/exit-code result))))))

;------------------------------------------------------------------------------ Layer 2

;; Tests
(deftest ^{:stratum 2} test-passing-run
  (testing "given every criterion passing → a valid report, written, verdict pass, exit 0"
    (let [{:keys [report paths] :as result} (run-in-temp (scenario-returning all-pass))]
      (is (qualification/valid-report? report))
      (is (= :verdict/pass (:report/verdict report)))
      (is (= command (:report/command report)))
      (is (= 1 (:report/issue report)))
      (is (re-matches #"[0-9a-f]{64}" (:report/fixture-hash report)))
      (is (.isFile (io/file (:edn paths))))
      (is (.isFile (io/file (:json paths))))
      (is (= 0 (qualification/exit-code result))))))

(deftest ^{:stratum 2} test-throwing-scenario-is-unrun
  (testing "given a scenario that throws ex-info, throws a keyword with throw+, or throws an AssertionError → every criterion unrun, still reported, exit 1"
    (doseq [scenario [(fn [] (throw (ex-info "boom" {:reason :fixture})))
                      (fn [] (throw+ :fixture/stop))
                      (fn [] (throw (AssertionError. "boom")))]]
      (let [{:keys [report paths] :as result} (run-in-temp scenario)]
        (is (qualification/valid-report? report))
        (is (= :scenario/threw (get-in report [:report/failure :error/kind])))
        (is (= #{:status/unrun} (set (map :criterion/status (:report/criteria report)))))
        (is (= :verdict/incomplete (:report/verdict report)))
        (is (nil? (:report/fixture-hash report)))
        (is (some? paths))
        (is (= 1 (qualification/exit-code result)))))))

(deftest ^{:stratum 2} test-unusable-output-is-unrun
  (testing "given malformed output, a fn in the fixtures or in observed state, or a result for an undeclared criterion → invalid output, every criterion unrun"
    (doseq [scenario [(fn [] {:scenario/results all-pass})
                      (fn [] {:scenario/fixtures {:connector identity}
                              :scenario/config {}
                              :scenario/results all-pass})
                      (scenario-returning (assoc-in all-pass [1 :criterion/observed] {:sink identity}))
                      (scenario-returning (conj all-pass {:criterion/id :c/other
                                                          :criterion/status :status/pass}))]]
      (let [{:keys [report]} (run-in-temp scenario)]
        (is (qualification/valid-report? report))
        (is (= :scenario/invalid-output (get-in report [:report/failure :error/kind])))
        (is (= :verdict/incomplete (:report/verdict report)))))))

(deftest ^{:stratum 2} test-unreported-criterion-is-not-a-pass
  (testing "given a scenario that reports one of two criteria → incomplete, exit 1"
    (let [result (run-in-temp (scenario-returning [(first all-pass)]))]
      (is (= :verdict/incomplete (get-in result [:report :report/verdict])))
      (is (= 1 (qualification/exit-code result))))))

(deftest ^{:stratum 2} test-hashes-repeat-across-runs
  (testing "given the same fixtures and config twice → the same hashes"
    (let [a (:report (run-in-temp (scenario-returning all-pass)))
          b (:report (run-in-temp (scenario-returning all-pass)))]
      (is (= (:report/fixture-hash a) (:report/fixture-hash b)))
      (is (= (:report/config-hash a) (:report/config-hash b))))))

(deftest ^{:stratum 2} test-unpinned-build-is-not-a-pass
  (testing "given every criterion passing on a dirty tree or without a commit → incomplete, exit 1, and the summary says why"
    (doseq [build [(assoc clean-build :build/clean? false)
                   (assoc clean-build :build/git-sha nil)]]
      (let [{:keys [report] :as result} (run-in-temp (scenario-returning all-pass) build)]
        (is (qualification/valid-report? report))
        (is (= :verdict/incomplete (:report/verdict report)))
        (is (= #{:status/pass} (set (map :criterion/status (:report/criteria report)))))
        (is (= 1 (qualification/exit-code result)))
        (is (= 5 (count (qualification/summary-lines result))))))))

(deftest ^{:stratum 2} test-summary-names-verdict-and-rows
  (testing "given a run → a verdict line, one line per criterion, and the report path"
    (let [lines (qualification/summary-lines (run-in-temp (scenario-returning all-pass)))]
      (is (= 4 (count lines)))
      (is (re-find #"pass, 2 of 2" (first lines))))))
