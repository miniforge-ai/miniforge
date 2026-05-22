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
  "Contract tests for read-artifact silent-nil behavior.

   `read-artifact` returns nil without logging anything when the MCP artifact
   file is absent. Noise-free callers (e.g. `run-session`) decide whether
   to escalate after consulting all artifact sources (MCP file + worktree
   promotions). An ERROR at the `read-artifact` layer was removed because
   it fired even when a valid worktree artifact covered the missing MCP file."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.artifact-session :as session]))

(deftest read-artifact-missing-file-is-silent-test
  (testing "read-artifact emits nothing when MCP artifact file is absent"
    (let [s   (session/create-session!)
          err (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (is (nil? (session/read-artifact s))
              "missing MCP file must return nil"))
        (is (str/blank? (str err))
            "read-artifact must not log anything when the artifact file is missing")
        (finally
          (session/cleanup-session! s))))))
