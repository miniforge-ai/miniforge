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
  "Regression test for Run 7: artifact file not found logs ERROR not WARN.
   The previous WARN level was misleading — a missing artifact is fatal
   (the implement phase fails with 0ms duration)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.artifact-session :as session]))

(deftest read-artifact-missing-file-logs-error-test
  (testing "missing artifact file prints ERROR (not WARN) to stderr"
    (let [s (session/create-session!)
          stderr-output (java.io.StringWriter.)]
      (try
        ;; Capture stderr output
        (binding [*err* stderr-output]
          (session/read-artifact s))
        (let [output (str stderr-output)]
          (is (re-find #"ERROR" output)
              "Should log ERROR level, not WARN")
          (is (re-find #"artifact file not found" output)
              "Should mention artifact file not found")
          (is (re-find #"MCP tool" output)
              "Should hint at root cause (MCP tool not called)"))
        (finally
          (session/cleanup-session! s))))))

(deftest read-artifact-missing-returns-nil-test
  (testing "returns nil when artifact file doesn't exist"
    (let [s (session/create-session!)]
      (try
        (is (nil? (session/read-artifact s)))
        (finally
          (session/cleanup-session! s))))))
