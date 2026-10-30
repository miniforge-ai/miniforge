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
(ns ai.miniforge.cli.main.commands.pr-monitor-primitives
  "Constants and shell primitives for the PR monitor command."
  (:require
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0

;; Constants and shell primitives
(def ^{:stratum 0} ms-per-second
  "Milliseconds in one second.
   Converts poll-interval-s (config/worklist unit) to poll-interval-ms
   (pr-lifecycle internal unit). Extracted per rule 006 — used in three sites."
  1000)

(def ^{:stratum 0} default-poll-interval-ms
  "Fallback used in run-monitor! when the monitor atom does not yet carry
   [:config :poll-interval-ms]. Mirrors the pr-lifecycle monitor default (60 s)."
  60000)

(defn ^{:stratum 0} sh!
  "Run a command; babashka.process/sh, kept as one seam for tests."
  [& args]
  (apply process/sh args))
