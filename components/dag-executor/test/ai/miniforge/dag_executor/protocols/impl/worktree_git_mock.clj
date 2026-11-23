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
(ns ai.miniforge.dag-executor.protocols.impl.worktree-git-mock
  "Helpers for tests that replace `worktree/run-git` with a mock. Kept out
   of the test namespaces that use them so those tests stay in Layer 0.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} subcommand
  "Args of a mocked `run-git` call from the git subcommand on. Drops the
   leading `-C <path>` and any `-c <name>=<value>` options before the
   subcommand, so a mock dispatches on the subcommand alone."
  [args]
  (loop [tail (drop 2 args)]
    (if (= "-c" (first tail))
      (recur (drop 2 tail))
      tail)))
