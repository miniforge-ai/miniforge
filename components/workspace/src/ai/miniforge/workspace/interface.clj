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

(ns ai.miniforge.workspace.interface
  "Workspace resolution — the blessed home for execution working-directory
   resolution (Fable §1.3 no-silent-downgrade)."
  (:require
   [ai.miniforge.workspace.core :as core]))

(def resolve-execution-workdir
  "Resolve the execution worktree path from ctx. Present → return it; absent +
   governed → throw `:anomalies/workdir-unresolved` (fail closed); absent +
   local → process CWD. The single sanctioned `(System/getProperty
   \"user.dir\")` read. See `core` ns."
  core/resolve-execution-workdir)
