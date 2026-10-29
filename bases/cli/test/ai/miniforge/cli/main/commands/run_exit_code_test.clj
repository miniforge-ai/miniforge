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
(ns ai.miniforge.cli.main.commands.run-exit-code-test
  "`run` exits non-zero whenever the run did not succeed, so scripts and CI
   can tell a failed workflow from a finished one."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.commands.resume :as resume]
   [ai.miniforge.cli.main.commands.run :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} exit-code
  "Run `run-cmd` with `opts`, the workflow runner and plan executor stubbed
   to return `result`. Returns the code passed to exit!, or :no-exit."
  [opts result]
  (let [code (atom :no-exit)]
    (with-redefs [shared/exit! (fn [c] (reset! code c) nil)
                  display/print-info (fn [& _])
                  display/print-error (fn [& _])
                  spec-parser/parse-spec-file (fn [_] {:spec/title "t"})
                  spec-parser/validate-spec (fn [_] {:valid? true})
                  workflow-runner/run-workflow-from-spec! (fn [& _] result)
                  plan-executor/execute-plan (fn [& _] result)
                  resume/resume-workflow (fn [& _] result)]
      (binding [*out* (java.io.StringWriter.)]
        (sut/run-cmd opts)))
    @code))

(defn- ^{:stratum 0} temp-file
  [suffix content]
  (let [f (str (fs/create-temp-file {:suffix suffix}))]
    (spit f content)
    f))

(deftest ^{:stratum 0} run-succeeded-test
  (is (sut/run-succeeded? {:execution/status :completed}))
  (is (sut/run-succeeded? {:success? true :tasks-failed 0}))
  (is (not (sut/run-succeeded? {:execution/status :failed})))
  (is (not (sut/run-succeeded? {:success? false :tasks-failed 2})))
  (is (not (sut/run-succeeded? nil))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} run-exit-code-test
  (let [md (temp-file ".md" "---\ntitle: t\n---\nbody")
        plan (temp-file ".edn" (pr-str {:plan/id "p" :plan/tasks []}))]
    (testing "a completed workflow returns normally (exit 0)"
      (is (= :no-exit (exit-code {:spec md} {:execution/status :completed}))))
    (testing "a failed workflow exits 1"
      (is (= 1 (exit-code {:spec md} {:execution/status :failed}))))
    (testing "a plan with failed tasks exits 1; a clean plan does not"
      (is (= 1 (exit-code {:spec plan} {:success? false :tasks-failed 1})))
      (is (= :no-exit (exit-code {:spec plan} {:success? true :tasks-failed 0}))))
    (testing "a resumed run that ends failed exits 1"
      (is (= 1 (exit-code {:resume "wf-1"} {:execution/status :failed}))))
    (testing "usage errors exit 1"
      (is (= 1 (exit-code {} nil)) "no spec argument")
      (is (= 1 (exit-code {:spec "/nonexistent/spec.md"} nil)) "missing file")
      (is (= 1 (exit-code {:spec (temp-file ".edn" "{:something :else}")} nil))
          "unrecognized format"))
    (testing "a spec that fails validation exits 1"
      (let [code (atom :no-exit)]
        (with-redefs [shared/exit! (fn [c] (reset! code c) nil)
                      display/print-info (fn [& _])
                      display/print-error (fn [& _])
                      spec-parser/parse-spec-file (fn [_] {:spec/title "t"})
                      spec-parser/validate-spec (fn [_] {:valid? false :errors ["bad"]})]
          (binding [*out* (java.io.StringWriter.)]
            (sut/run-cmd {:spec md})))
        (is (= 1 @code))))))
