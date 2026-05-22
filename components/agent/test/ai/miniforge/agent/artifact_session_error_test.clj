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
;; Helpers

(defn- make-temp-workdir
  "Create an isolated temp directory and return its path string. Callers
   are responsible for cleanup via `cleanup-dir!`."
  []
  (str (Files/createTempDirectory
        "miniforge-test-workdir-"
        (into-array FileAttribute []))))

(defn- cleanup-dir!
  "Delete a directory and all its contents recursively."
  [path]
  (doseq [f (reverse (file-seq (io/file path)))]
    (.delete ^java.io.File f)))

(defn- write-plan-edn!
  "Write a minimal plan.edn into <workdir>/.miniforge/ so
   read-worktree-artifact finds a parseable plan for the :plan role."
  [workdir]
  (let [mf-dir (io/file workdir ".miniforge")]
    (.mkdirs mf-dir)
    (spit (io/file mf-dir "plan.edn")
          "{:plan/id \"550e8400-e29b-41d4-a716-446655440000\" :plan/name \"t\" :plan/tasks []}")))

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
    ;; Isolated temp dir so we never accidentally pick up real
    ;; .miniforge/*.edn files from the repo root.
    (let [workdir       (make-temp-workdir)
          context       {:execution/worktree-path workdir}
          stderr-output (java.io.StringWriter.)]
      (try
        (binding [*err* stderr-output]
          (session/with-session context (fn [_session] :noop)))
        (let [output (str stderr-output)]
          (is (re-find #"WARN: no artifact found after session" output)
              "Should emit the :warn/no-artifact-found diagnostic when neither artifact source produced a result")
          (is (re-find #"checked MCP path" output)
              "Diagnostic must mention what was checked so post-mortem readers can trace the miss")
          (is (not (re-find #"ERROR" output))
              "Must use WARN level, not ERROR, for empty-artifact diagnostic"))
        (finally
          (cleanup-dir! workdir))))))

(deftest with-session-no-warn-when-worktree-artifact-present-test
  (testing "with-session does NOT emit any diagnostic when worktree artifact exists"
    (let [workdir       (make-temp-workdir)
          _             (write-plan-edn! workdir)
          context       {:execution/worktree-path workdir}
          stderr-output (java.io.StringWriter.)]
      (try
        (binding [*err* stderr-output]
          (session/with-session context (fn [_session] :noop)))
        (let [output (str stderr-output)]
          (is (not (re-find #"WARN.*no artifact found" output))
              "Should NOT emit 'no artifact found' WARN when worktree artifact exists"))
        (finally
          (cleanup-dir! workdir))))))

;; ---------------------------------------------------------------------------
;; Case 1: valid worktree artifact (:plan key populated), nil MCP → no WARN

(deftest with-session-no-warn-when-plan-key-populated-mcp-nil-test
  (testing "no WARN when worktree has :plan key and MCP artifact is absent"
    ;; Container-promotion path: agent wrote .miniforge/plan.edn (with :plan/id
    ;; populated) but never called MCP submit_artifact. The run-session path
    ;; MUST treat a populated worktree-artifact as a successful delivery and
    ;; suppress :warn/no-artifact-found entirely.
    (let [workdir (make-temp-workdir)
          _       (write-plan-edn! workdir)
          ctx     {:execution/worktree-path workdir}
          err     (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (let [result (session/with-session ctx (fn [_session] :plan-delivered))]
            (is (= :plan-delivered (:llm-result result))
                "body-fn return value must be preserved")
            (is (map? (get-in result [:worktree-artifacts :plan]))
                ":plan key must be populated from the worktree scan")
            (is (some? (get-in result [:worktree-artifacts :plan :plan/id]))
                ":plan/id must be present after UUID parse-through")
            (is (nil? (:artifact result))
                "MCP artifact must be nil — no MCP submission occurred")
            (is (not (re-find #"WARN.*no artifact found" (str err)))
                ":warn/no-artifact-found must be suppressed when worktree :plan key is populated")
            (is (not (re-find #"WARN" (str err)))
                "no WARN of any kind expected when worktree artifact covers the miss")))
        (finally
          (cleanup-dir! workdir))))))

;; ---------------------------------------------------------------------------
;; Case 3: worktree artifact fails to parse → parse WARN emitted,
;;          :warn/no-artifact-found NOT emitted

(deftest with-session-parse-warn-not-no-artifact-warn-when-malformed-edn-test
  (testing ":warn/worktree-artifact-parse fires but :warn/no-artifact-found is suppressed when file exists but is malformed"
    ;; If the agent wrote plan.edn but it contains invalid EDN, the parse
    ;; WARN is the correct single diagnostic. :warn/no-artifact-found must
    ;; be suppressed because the file DID exist — the problem is content,
    ;; not absence.
    (let [workdir (make-temp-workdir)
          mf-dir  (io/file workdir ".miniforge")
          _       (.mkdirs mf-dir)
          _       (spit (io/file mf-dir "plan.edn") "{{{not valid edn at all")
          ctx     {:execution/worktree-path workdir}
          err     (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (session/with-session ctx (constantly :malformed)))
        (let [output (str err)]
          (is (re-find #"failed to parse worktree artifact" output)
              ":warn/worktree-artifact-parse must fire to alert on the malformed file")
          (is (not (re-find #"no artifact found after session" output))
              ":warn/no-artifact-found must NOT fire when the file existed — parse WARN covers it")
          (is (not (re-find #"ERROR" output))
              "ERROR must never appear — the issue is a WARN-level parse failure"))
        (finally
          (cleanup-dir! workdir))))))

;; ---------------------------------------------------------------------------
;; Case 4: both MCP artifact and worktree artifact present → no WARN

(deftest with-session-no-warn-when-both-mcp-and-worktree-present-test
  (testing "no WARN when both MCP artifact and worktree artifact are present"
    ;; Belt-and-suspenders case: agent called both MCP submit_artifact AND
    ;; wrote .miniforge/plan.edn. No warning should fire — both channels
    ;; delivered work product. Specifically verifies the WARN gate correctly
    ;; checks `(nil? artifact)` and short-circuits when MCP is present.
    (let [workdir (make-temp-workdir)
          _       (write-plan-edn! workdir)
          ctx     {:execution/worktree-path workdir}
          err     (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (let [result (session/with-session ctx
                         (fn [sess]
                           ;; Simulate MCP submit_artifact being called
                           (spit (:artifact-path sess)
                                 "{:code/id \"550e8400-e29b-41d4-a716-446655440000\" :code/summary \"both channels\"}")
                           :both-present))]
            (is (= :both-present (:llm-result result))
                "body-fn return value must be preserved")
            (is (map? (:artifact result))
                "MCP artifact must be read when present")
            (is (map? (get-in result [:worktree-artifacts :plan]))
                ":plan key must be populated from the worktree scan")
            (is (not (re-find #"WARN" (str err)))
                "no WARN of any kind when both channels delivered")))
        (finally
          (cleanup-dir! workdir))))))

;; ---------------------------------------------------------------------------
;; Case 5: explicit-workdir? false → no WARN regardless of artifact state

(deftest with-session-no-warn-when-explicit-workdir-false-test
  (testing "no WARN emitted when no explicit workdir is provided, even with no artifact"
    ;; When called without :execution/worktree-path the worktree scan is
    ;; intentionally skipped. An empty result from the skipped scan must NOT
    ;; be misread as a genuine 'nothing found' failure. This protects against
    ;; false WARN from runs where the worktree path is simply not threaded
    ;; through (e.g. test scaffolding, ad-hoc calls).
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (let [result (session/with-session {}
                       (fn [_session] :no-workdir-result))]
          (is (= :no-workdir-result (:llm-result result))
              "body-fn return value must pass through")
          (is (nil? (:artifact result))
              "no MCP artifact expected — none was written")
          (is (empty? (:worktree-artifacts result))
              "worktree-artifacts must be empty when no explicit workdir")
          (is (not (re-find #"WARN" (str err)))
              "no WARN must fire when worktree scan was not engaged")
          (is (not (re-find #"ERROR" (str err)))
              "ERROR must never appear from the artifact-session layer")))))

  (testing "no WARN when explicit-workdir? false regardless of body-fn return"
    ;; Verify the gate holds across different body-fn return shapes.
    (doseq [ret-val [:some-keyword nil {} [] :already-implemented]]
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (session/with-session {} (constantly ret-val)))
        (is (not (re-find #"WARN" (str err)))
            (str "no WARN for return value " ret-val " without explicit workdir"))))))

;; ---------------------------------------------------------------------------
;; Case 6: already-satisfied plan response → no false ERROR in output

(deftest with-session-no-error-for-already-implemented-response-test
  (testing "no false ERROR when agent writes :already-implemented response to worktree"
    ;; The implementer writes {:status :already-implemented :summary \"...\"}
    ;; to .miniforge/implement.edn when the work is already done. This is a
    ;; valid successful artifact. run-session must treat it as a populated
    ;; worktree-artifact and suppress :warn/no-artifact-found, and under no
    ;; circumstance emit ERROR-level output for this success case.
    (let [workdir (make-temp-workdir)
          mf-dir  (io/file workdir ".miniforge")
          _       (.mkdirs mf-dir)
          _       (spit (io/file mf-dir "implement.edn")
                        (pr-str {:status   :already-implemented
                                 :summary  "All acceptance criteria already met"}))
          ctx     {:execution/worktree-path workdir}
          err     (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (let [result (session/with-session ctx (constantly :already-impl))]
            (is (= :already-impl (:llm-result result))
                "body-fn return must be preserved for already-implemented case")
            (is (map? (get-in result [:worktree-artifacts :implement]))
                ":implement key must be populated from the worktree scan")
            (is (= :already-implemented
                   (get-in result [:worktree-artifacts :implement :status]))
                ":status :already-implemented must survive the EDN round-trip")
            (is (not (re-find #"ERROR" (str err)))
                "ERROR must never be emitted for an already-implemented response")
            (is (not (re-find #"no artifact found after session" (str err)))
                ":warn/no-artifact-found must be suppressed when implement.edn is present")))
        (finally
          (cleanup-dir! workdir)))))

  (testing "already-implemented with no MCP artifact does not emit no-artifact WARN"
    ;; Regression guard: the old code path surfaced :warn/no-artifact-found
    ;; (perceived as ERROR by operators) even when the agent wrote a valid
    ;; already-implemented response. Verify the fix holds for this exact shape.
    (let [workdir (make-temp-workdir)
          mf-dir  (io/file workdir ".miniforge")
          _       (.mkdirs mf-dir)
          _       (spit (io/file mf-dir "plan.edn")
                        (pr-str {:status  :already-implemented
                                 :plan/id "550e8400-e29b-41d4-a716-446655440000"
                                 :summary "Plan already satisfied"}))
          ctx     {:execution/worktree-path workdir}
          err     (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (session/with-session ctx (constantly :already-plan)))
        (is (not (re-find #"no artifact found" (str err)))
            "no-artifact WARN must not fire when plan.edn contains already-implemented status")
        (is (not (re-find #"ERROR" (str err)))
            "no ERROR output for already-implemented plan response")
        (finally
          (cleanup-dir! workdir))))))
