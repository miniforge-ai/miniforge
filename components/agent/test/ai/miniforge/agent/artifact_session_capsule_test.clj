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

(ns ai.miniforge.agent.artifact-session-capsule-test
  "Tests for capsule-aware artifact sessions (N11 §6.3-6.4).
   Verifies with-session dispatches correctly between host and capsule modes."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.agent.artifact-session :as session]
   [ai.miniforge.dag-executor.executor :as executor]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ helpers

(defn- delete-dir!
  "Recursively delete a directory tree."
  [path]
  (doseq [^java.io.File f (reverse (file-seq (io/file path)))]
    (.delete f)))

(defn- local-cat-exec!
  "Stub exec! that reads 'cat <path>' from the local filesystem.
   All other commands (mkdir, rm, git status, etc.) succeed with empty output.
   Used for tests that need a real workdir on disk without a live executor."
  [_executor _env-id cmd _opts]
  (if (str/starts-with? cmd "cat ")
    (let [path (subs cmd 4)
          f    (io/file path)]
      {:ok?  true
       :data {:exit-code (if (.exists f) 0 1)
              :stdout    (if (.exists f) (slurp f) "")
              :stderr    ""}})
    {:ok? true :data {:exit-code 0 :stdout "" :stderr ""}}))

;; run-session is private; accessed via ns-resolve for white-box testing.
(def ^:private run-session*
  @(ns-resolve 'ai.miniforge.agent.artifact-session 'run-session))

;------------------------------------------------------------------------------ Layer 0
;; Mock executor infrastructure

(def ^:private capsule-workdir
  "/workspace")

(def ^:private capsule-session-dir
  (str capsule-workdir "/.miniforge-session"))

(def ^:private capsule-cleanup-command
  (str "rm -rf " capsule-session-dir))

(defn- mock-execute!
  "Mock executor that records commands and returns success."
  [log]
  (fn [_executor _env-id command & [_opts]]
    (swap! log conj command)
    (result/ok {:stdout "" :stderr "" :exit-code 0})))

(defn- mock-execute-with-artifact!
  "Mock executor that records commands and returns artifact EDN for cat commands."
  [log]
  (fn [_executor _env-id command & [_opts]]
    (swap! log conj command)
    (cond
      (and (string? command) (str/starts-with? command "cat ") (str/includes? command "artifact.edn"))
      (result/ok {:stdout "{:code/id \"a1b2c3d4-e5f6-7890-abcd-ef1234567890\" :code/description \"test\"}"
                  :stderr ""
                  :exit-code 0})

      (and (string? command) (str/starts-with? command "cat ") (str/includes? command "implement.edn"))
      (result/ok {:stdout "{:status :already-implemented :summary \"already there\"}"
                  :stderr ""
                  :exit-code 0})

      :else
      (result/ok {:stdout "" :stderr "" :exit-code 0}))))

;------------------------------------------------------------------------------ Layer 1
;; with-session dispatch tests

(deftest with-session-local-mode-test
  (testing "local mode uses host session (same shape as with-artifact-session)"
    (let [context {:execution/mode :local}
          result (session/with-session context
                   (fn [session]
                     (is (string? (:mcp-config-path session)))
                     (is (string? (:artifact-path session)))
                     (is (nil? (:capsule? session)))
                     :llm-response))]
      (is (= :llm-response (:llm-result result)))
      (is (= :host (:session-mode result)))
      (is (nil? (:artifact result))))))

(deftest with-session-missing-mode-defaults-to-host-test
  (testing "missing :execution/mode falls through to host"
    (let [result (session/with-session {}
                   (fn [session]
                     (is (nil? (:capsule? session)))
                     :ok))]
      (is (= :host (:session-mode result))))))

(deftest with-session-governed-mode-test
  (testing "governed mode with executor uses capsule session"
    (let [log (atom [])]
      (with-redefs [executor/execute! (mock-execute! log)]
        (let [context {:execution/mode :governed
                       :execution/executor :mock-executor
                       :execution/environment-id "env-123"
                       :execution/worktree-path capsule-workdir}
              result (session/with-session context
                       (fn [session]
                         (is (true? (:capsule? session)))
                         (is (= capsule-session-dir (:dir session)))
                         (is (= :mock-executor (:executor session)))
                         :capsule-response))]
          (is (= :capsule-response (:llm-result result)))
          (is (= :capsule (:session-mode result)))
          (is (nil? (:context-misses result)))
          (is (map? (:pre-session-snapshot result)))
          ;; Should have executed mkdir, config writes, and cleanup
          (is (pos? (count @log))))))))

(deftest with-session-governed-reads-artifact-test
  (testing "governed mode reads and parses artifact from capsule"
    (let [log (atom [])]
      (with-redefs [executor/execute! (mock-execute-with-artifact! log)]
        (let [context {:execution/mode :governed
                       :execution/executor :mock
                       :execution/environment-id "env-456"
                       :execution/worktree-path capsule-workdir}
              result (session/with-session context
                       (fn [_session] :done))]
          (is (some? (:artifact result)))
          (is (uuid? (:code/id (:artifact result)))))))))

(deftest with-session-governed-cleanup-on-exception-test
  (testing "capsule session cleans up even on exception"
    (let [log (atom [])
          context {:execution/mode :governed
                   :execution/executor :mock
                   :execution/environment-id "env-789"
                   :execution/worktree-path capsule-workdir}]
      (with-redefs [executor/execute! (mock-execute! log)]
        (is (thrown? Exception
              (session/with-session context
                (fn [_session]
                  (throw (ex-info "test error" {}))))))
        (is (some #(= capsule-cleanup-command %) @log))))))

;------------------------------------------------------------------------------ Layer 2
;; Context cache dispatch tests

(deftest write-context-cache-for-session-host-test
  (testing "host session uses spit-based write"
    (let [s (session/create-session!)]
      (try
        (session/write-context-cache-for-session! s {"src/foo.clj" "(ns foo)"})
        (is (.exists (java.io.File. (str (:dir s) "/context-cache.edn"))))
        (finally
          (session/cleanup-session! s))))))

(deftest write-context-cache-for-session-capsule-test
  (testing "capsule session uses executor-based write"
    (let [log (atom [])
          s {:dir "/workspace/.miniforge-session"
             :capsule? true
             :exec! (mock-execute! log)
             :executor :mock
             :environment-id "env-test"
             :workdir capsule-workdir}]
      (session/write-context-cache-for-session! s {"src/foo.clj" "(ns foo)"})
      (is (some #(.contains (str %) "context-cache.edn") @log)))))

;------------------------------------------------------------------------------ Layer 3
;; UUID parsing in capsule artifact

(deftest read-capsule-artifact-parses-uuids-test
  (testing "UUID strings are converted to java.util.UUID"
    (let [log (atom [])
          s {:artifact-path "/workspace/.miniforge-session/artifact.edn"
             :capsule? true
             :exec! (mock-execute-with-artifact! log)
             :executor :mock
             :environment-id "env-uuid"
             :workdir capsule-workdir}
          artifact (session/read-capsule-artifact s)]
      (is (some? artifact))
      (is (uuid? (:code/id artifact))))))

(deftest read-capsule-worktree-artifact-test
  (testing "reads .miniforge/<role>.edn from inside the capsule worktree"
    (let [log (atom [])
          s {:capsule? true
             :exec! (mock-execute-with-artifact! log)
             :executor :mock
             :environment-id "env-role"
             :workdir capsule-workdir}
          artifact (session/read-capsule-worktree-artifact s :implement)]
      (is (= :already-implemented (:status artifact)))
      (is (= "already there" (:summary artifact))))))

;------------------------------------------------------------------------------ Layer 4
;; :explicit-workdir? flag and WARN suppression (artifact-warning-suppression PR)

(deftest create-capsule-session-sets-explicit-workdir-test
  (testing "create-capsule-session! always sets :explicit-workdir? true"
    ;; The 4-arity form accepts an inject execute-fn, so the test never
    ;; touches requiring-resolve or a live Docker executor.
    (let [dir (str (System/getProperty "java.io.tmpdir") "/caps-test-" (random-uuid))]
      (.mkdirs (io/file dir))
      (try
        (let [s (session/create-capsule-session! ::stub "env-1" dir local-cat-exec!)]
          (is (true? (:explicit-workdir? s))
              ":explicit-workdir? must be true so run-session scans worktree artifacts")
          (is (= dir (:workdir s)))
          (is (true? (:capsule? s))))
        (finally
          (delete-dir! dir))))))

(deftest capsule-worktree-artifact-suppresses-warn-test
  (testing "no WARN when .miniforge/plan.edn exists in capsule workdir (no MCP artifact)"
    (let [dir       (str (System/getProperty "java.io.tmpdir") "/caps-warn-" (random-uuid))
          plan-file (io/file dir ".miniforge" "plan.edn")]
      (.mkdirs (.getParentFile plan-file))
      ;; Write a minimal parseable plan artifact to the capsule workdir
      (spit plan-file "{:plan/summary \"stub\" :plan/tasks []}")
      (try
        (let [session {:workdir           dir
                       :explicit-workdir? true
                       :capsule?          true
                       ;; artifact-path does NOT exist → read-capsule-artifact returns nil
                       :artifact-path     (str dir "/.miniforge-session/artifact.edn")
                       :exec!             local-cat-exec!
                       :executor          ::stub
                       :environment-id    "env-1"}
              err    (java.io.StringWriter.)
              result (binding [*err* err]
                       (run-session* session
                                     (constantly :ok)
                                     session/read-capsule-artifact
                                     (constantly nil)   ; cleanup noop
                                     :capsule))]
          (is (not (str/includes? (str err) "WARN: no artifact found"))
              "WARN must be suppressed when worktree artifact covers the missing MCP file")
          (is (contains? (:worktree-artifacts result) :plan)
              "worktree-artifacts must include :plan from .miniforge/plan.edn"))
        (finally
          (delete-dir! dir))))))

(deftest capsule-warn-fires-when-both-sources-absent-test
  (testing "WARN fires when both MCP artifact and all worktree artifacts are absent"
    (let [dir     (str (System/getProperty "java.io.tmpdir") "/caps-nowarn-" (random-uuid))]
      (.mkdirs (io/file dir))
      (try
        (let [session {:workdir           dir
                       :explicit-workdir? true
                       :capsule?          true
                       :artifact-path     (str dir "/.miniforge-session/artifact.edn")
                       :exec!             local-cat-exec!
                       :executor          ::stub
                       :environment-id    "env-2"}
              err    (java.io.StringWriter.)]
          (binding [*err* err]
            (run-session* session
                          (constantly :ok)
                          session/read-capsule-artifact
                          (constantly nil)
                          :capsule))
          ;; Both sources absent → WARN must have been written
          (is (str/includes? (str err) "WARN: no artifact found")
              "WARN must fire when both MCP and worktree artifacts are absent"))
        (finally
          (delete-dir! dir))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.artifact-session-capsule-test)

  :leave-this-here)
