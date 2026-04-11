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
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.agent.artifact-session :as session]
   [ai.miniforge.dag-executor.executor :as executor]))

;------------------------------------------------------------------------------ Layer 0
;; Mock executor infrastructure

(def ^:dynamic *exec-log* nil)

(defn mock-execute!
  "Mock executor that records commands and returns success."
  [_executor _env-id command & [_opts]]
  (when *exec-log*
    (swap! *exec-log* conj command))
  {:ok? true :data {:stdout "" :stderr "" :exit-code 0}})

(defn mock-execute-with-artifact!
  "Mock executor that returns artifact EDN for cat commands."
  [_executor _env-id command & [_opts]]
  (when *exec-log*
    (swap! *exec-log* conj command))
  (if (and (string? command) (str/starts-with? command "cat ") (str/includes? command "artifact.edn"))
    {:ok? true :data {:stdout "{:code/id \"a1b2c3d4-e5f6-7890-abcd-ef1234567890\" :code/description \"test\"}"
                      :stderr "" :exit-code 0}}
    {:ok? true :data {:stdout "" :stderr "" :exit-code 0}}))

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
    (with-redefs [executor/execute! mock-execute!]
      (let [log (atom [])
            context {:execution/mode :governed
                     :execution/executor :mock-executor
                     :execution/environment-id "env-123"
                     :execution/worktree-path "/workspace"}
            result (binding [*exec-log* log]
                     (session/with-session context
                       (fn [session]
                         (is (true? (:capsule? session)))
                         (is (= "/workspace/.miniforge-session" (:dir session)))
                         (is (= :mock-executor (:executor session)))
                         :capsule-response)))]
        (is (= :capsule-response (:llm-result result)))
        (is (= :capsule (:session-mode result)))
        (is (nil? (:context-misses result)))
        (is (map? (:pre-session-snapshot result)))
        ;; Should have executed mkdir, config writes, and cleanup
        (is (pos? (count @log)))))))

(deftest with-session-governed-reads-artifact-test
  (testing "governed mode reads and parses artifact from capsule"
    (with-redefs [executor/execute! mock-execute-with-artifact!]
      (let [context {:execution/mode :governed
                     :execution/executor :mock
                     :execution/environment-id "env-456"
                     :execution/worktree-path "/workspace"}
            result (session/with-session context
                     (fn [_session] :done))]
        (is (some? (:artifact result)))
        (is (uuid? (:code/id (:artifact result))))))))

(deftest with-session-governed-cleanup-on-exception-test
  (testing "capsule session cleans up even on exception"
    (with-redefs [executor/execute! mock-execute!]
      (let [log (atom [])
            context {:execution/mode :governed
                     :execution/executor :mock
                     :execution/environment-id "env-789"
                     :execution/worktree-path "/workspace"}]
        (is (thrown? Exception
              (binding [*exec-log* log]
                (session/with-session context
                  (fn [_session]
                    (throw (ex-info "test error" {})))))))
        ;; Cleanup rm -rf should have been called
        (is (some #(.contains (str %) "rm -rf") @log))))))

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
             :exec! mock-execute!
             :executor :mock
             :environment-id "env-test"
             :workdir "/workspace"}]
      (binding [*exec-log* log]
        (session/write-context-cache-for-session! s {"src/foo.clj" "(ns foo)"}))
      (is (some #(.contains (str %) "context-cache.edn") @log)))))

;------------------------------------------------------------------------------ Layer 3
;; UUID parsing in capsule artifact

(deftest read-capsule-artifact-parses-uuids-test
  (testing "UUID strings are converted to java.util.UUID"
    (let [s {:artifact-path "/workspace/.miniforge-session/artifact.edn"
             :capsule? true
             :exec! mock-execute-with-artifact!
             :executor :mock
             :environment-id "env-uuid"
             :workdir "/workspace"}
          artifact (session/read-capsule-artifact s)]
      (is (some? artifact))
      (is (uuid? (:code/id artifact))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.artifact-session-capsule-test)

  :leave-this-here)
