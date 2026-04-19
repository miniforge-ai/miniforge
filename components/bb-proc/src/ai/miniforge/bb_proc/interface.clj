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

(ns ai.miniforge.bb-proc.interface
  "Subprocess helpers for Babashka tasks. Pass-through to `core`."
  (:require [ai.miniforge.bb-proc.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn run!
  "Run a command inheriting stdio. Throws ex-info on non-zero exit.
   Accepts an optional opts map as the first arg (like `p/shell`)."
  [& args]
  (apply core/run! args))

(defn run-bg!
  "Start a command in the background. Returns the process handle.
   Caller is responsible for destroying it via `destroy!`."
  [& args]
  (apply core/run-bg! args))

(defn sh
  "Run a command, capture stdout/stderr, return the result map.
   Never throws — caller inspects `:exit`."
  [& args]
  (apply core/sh args))

(defn installed?
  "True if `cmd` resolves on PATH."
  [cmd]
  (core/installed? cmd))

(defn destroy!
  "Destroy a background process and wait briefly for it to exit."
  [proc]
  (core/destroy! proc))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (installed? "git")
  (sh "echo" "hello")

  :leave-this-here)
