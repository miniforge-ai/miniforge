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

(ns ai.miniforge.agent.artifact-session-error-test
  "Regression tests for artifact-session diagnostic message behaviour.

   Covers two invariants:
   1. `read-artifact` — absent MCP file returns nil silently (no ERROR).
      The MCP artifact is an optional submission channel; a missing file
      must not pollute the terminal with a false ERROR on successful plan-file runs.
   2. `with-session` — emits WARN (not ERROR) only when BOTH the MCP artifact
      path and all worktree role paths are empty, so genuine 'nothing found'
      cases remain visible during post-mortem."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.artifact-session :as session])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; read-artifact — MCP file absent → nil, no output

(deftest read-artifact-missing-file-silent-test
  (testing "absent MCP artifact file returns nil without emitting anything to stderr"
    (let [s              (session/create-session!)
          stderr-output  (java.io.StringWriter.)]
      (try
        (binding [*err* stderr-output]
          (let [result (session/read-artifact s)]
            (is (nil? result)
                "Should return nil when MCP artifact file is absent")
            (is (str/blank? (str stderr-output))
                "Should emit nothing to stderr — MCP artifact is optional")
            (is (not (re-find #"ERROR" (str stderr-output)))
                "Must NOT emit ERROR for a missing MCP artifact file")))
        (finally
          (session/cleanup-session! s))))))

(deftest read-artifact-missing-returns-nil-test
  (testing "returns nil when artifact file doesn't exist"
    (let [s (session/create-session!)]
      (try
        (is (nil? (session/read-artifact s)))
        (finally
          (session/cleanup-session! s))))))

;; ---------------------------------------------------------------------------
;; with-session — WARN only when both channels empty

(deftest with-session-warn-when-both-absent-test
  (testing "with-session emits WARN when both MCP artifact and all worktree paths are absent"
    ;; Use an isolated temp dir so we never accidentally pick up real
    ;; .miniforge/*.edn files from the repo root.
    (let [workdir       (str (Files/createTempDirectory
                              "miniforge-test-workdir-"
                              (into-array FileAttribute [])))
          context       {:execution/worktree-path workdir}
          stderr-output (java.io.StringWriter.)]
      (try
        (binding [*err* stderr-output]
          (session/with-session context (fn [_session] :noop)))
        (let [output (str stderr-output)]
          (is (re-find #"WARN" output)
              "Should emit WARN when neither artifact source produced a result")
          (is (not (re-find #"ERROR" output))
              "Must use WARN level, not ERROR, for empty-artifact diagnostic"))
        (finally
          (doseq [f (reverse (file-seq (io/file workdir)))]
            (.delete ^java.io.File f)))))))

(deftest with-session-no-warn-when-worktree-artifact-present-test
  (testing "with-session does NOT emit any diagnostic when worktree artifact exists"
    ;; Write a minimal plan.edn into .miniforge/ so read-worktree-artifact
    ;; finds it, making worktree-artifacts non-empty.
    (let [workdir       (str (Files/createTempDirectory
                              "miniforge-test-workdir-"
                              (into-array FileAttribute [])))
          mf-dir        (io/file workdir ".miniforge")
          _             (do (.mkdirs mf-dir)
                            (spit (io/file mf-dir "plan.edn")
                                  "{:plan/id \"550e8400-e29b-41d4-a716-446655440000\" :plan/name \"t\" :plan/tasks []}"))
          context       {:execution/worktree-path workdir}
          stderr-output (java.io.StringWriter.)]
      (try
        (binding [*err* stderr-output]
          (session/with-session context (fn [_session] :noop)))
        (let [output (str stderr-output)]
          (is (not (re-find #"WARN.*no artifact found" output))
              "Should NOT emit 'no artifact found' WARN when worktree artifact exists"))
        (finally
          (doseq [f (reverse (file-seq (io/file workdir)))]
            (.delete ^java.io.File f)))))))
