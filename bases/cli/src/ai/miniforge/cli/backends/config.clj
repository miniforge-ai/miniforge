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
(ns ai.miniforge.cli.backends.config
  "Resource-backed backend config for Miniforge CLI.

   Split out of `ai.miniforge.cli.backends` (rule 210: the combined
   namespace measured 7 real layers, max 3) — this namespace owns the
   classpath-loaded config and the derived :backend/specs and
   :backend/defaults. Status checking, backend-info assembly, and
   validation live in sibling `ai.miniforge.cli.backends.status`;
   display formatting and the top-level listing/printing entry points
   stay in the parent namespace."
  (:require
   [ai.miniforge.cli.resource-config :as resource-config]))

;------------------------------------------------------------------------------ Layer 0

;; Backend specifications
(def ^{:stratum 0} ^:private backend-config-resource
  "Classpath resource path for backend metadata and defaults."
  "config/cli/backends.edn")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private backend-config
  (resource-config/merged-resource-config
   backend-config-resource
   nil
   {:backend/defaults {:current :codex}
    :backend/specs {}}))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} backend-specs
  (:backend/specs backend-config))

(def ^{:stratum 2} backend-defaults
  (:backend/defaults backend-config))
