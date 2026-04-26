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

(ns ai.miniforge.bb-platform.interface
  "Public API for cross-platform OS detection, package install routing,
   and the `bb check:platform` task. Pass-through to `core`."
  (:require [ai.miniforge.bb-platform.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Pure

(defn detect-os
  "Map a Java `os.name` string to one of #{:macos :linux :windows}."
  [os-name]
  (core/detect-os os-name))

(defn install-plan
  "Build an install plan map. See core/install-plan for keys."
  [opts]
  (core/install-plan opts))

(defn upgrade-plan
  "Build an upgrade plan map. See core/upgrade-plan for keys."
  [opts]
  (core/upgrade-plan opts))

(defn install-plan-java
  [opts]
  (core/install-plan-java opts))

(defn install-plan-markdownlint
  [opts]
  (core/install-plan-markdownlint opts))

(defn check
  "Assemble a platform check report from supplied facts."
  [opts]
  (core/check opts))

;------------------------------------------------------------------------------ Layer 1
;; Side-effecting

(defn os-key
  "Detect the OS of the current process."
  []
  (core/os-key))

(defn installed?
  "True if `cmd` is on PATH (cross-platform)."
  [cmd]
  (core/installed? cmd))

(defn execute-plan!
  "Run the side effects described by a plan. Returns the plan, possibly
   augmented with :result on :run actions."
  [plan]
  (core/execute-plan! plan))

(defn install!
  "Build and execute an install plan for `formula` on the current OS."
  [formula]
  (core/install! formula))

(defn upgrade!
  "Build and execute an upgrade plan for `formula` on the current OS."
  [formula]
  (core/upgrade! formula))

(defn check-current
  "Gather facts and return a check report for the current process."
  [tool-cmds]
  (core/check-current tool-cmds))

(defn print-check!
  "Print a human-friendly view of a check report."
  [report]
  (core/print-check! report))
