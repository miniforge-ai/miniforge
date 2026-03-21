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

(ns ai.miniforge.cli.spec-parser
  "CLI entry point for spec parsing.

   Delegates to the spec-parser component for all parsing, normalization,
   and validation. This thin wrapper exists so existing CLI callers don't
   need to change their require paths."
  (:require
   [ai.miniforge.spec-parser.interface :as spec-parser]))

;------------------------------------------------------------------------------ Public API
;; Thin delegation to spec-parser component

(defn parse-spec-file
  "Parse a workflow specification file and normalize to canonical :spec/* format.

   Supported formats:
   - .edn      - Clojure EDN (recommended)
   - .json     - JSON
   - .md       - Markdown with YAML frontmatter
   - .yaml/.yml - YAML (coming soon)

   Returns normalized spec map ready for workflow engine."
  [path]
  (spec-parser/parse-spec-file path))

(defn validate-spec
  "Validate a normalized spec against the Malli SpecPayload schema.

   Returns:
   - {:valid? true} if valid
   - {:valid? false :errors [...]} if invalid"
  [spec]
  (spec-parser/validate-spec spec))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example: Parse EDN spec
  (parse-spec-file "examples/workflows/simple-refactor.edn")

  ;; Example: Parse JSON spec
  (parse-spec-file "examples/workflows/add-tests.json")

  :end)
