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

(ns ai.miniforge.bb-out.interface
  "Status output helpers for Babashka tasks. Pass-through to `core`."
  (:require [ai.miniforge.bb-out.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn section
  "Print a section header: `==> msg`. Stdout."
  [msg]
  (core/section msg))

(defn step
  "Indented progress line. Stdout."
  [msg]
  (core/step msg))

(defn ok
  "Success line. Stdout."
  [msg]
  (core/ok msg))

(defn warn
  "Warning line. Stderr."
  [msg]
  (core/warn msg))

(defn fail
  "Failure line. Stderr."
  [msg]
  (core/fail msg))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (section "demo")
  (step "doing thing")
  (ok "done")
  (warn "heads up")
  (fail "nope")

  :leave-this-here)
