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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.inventory
  "The design spec's complete `.mdc` file inventory (Section 3 of
   work/designs/mdc-to-pack-field-mapping.edn) — every file the
   MDC-to-Pack compiler must produce a rule for, and its expected
   :rule/id. Part of the reference implementation for the
   MDC-to-Pack Rule Field Mapping acceptance spec.

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210: slice 3/7 of a stratum-lint SL003 split train — see
   `mdc-to-pack-mapping-test.dewey` for slice 1 and the full train
   rationale). This is plain data with no dependencies, so it was
   always a layer-0 island.

   Layer 0: complete-inventory")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} complete-inventory
  "All .mdc files and their expected rule IDs from Section 3."
  {"foundations/stratified-design.mdc"       :std/stratified-design
   "foundations/simple-made-easy.mdc"        :std/simple-made-easy
   "foundations/code-quality.mdc"            :std/code-quality
   "foundations/result-handling.mdc"         :std/result-handling
   "foundations/validation-boundaries.mdc"   :std/validation-boundaries
   "foundations/specification-standards.mdc" :std/specification-standards
   "foundations/localization.mdc"            :std/localization
   "languages/clojure.mdc"                  :std/clojure
   "languages/python.mdc"                   :std/python
   "frameworks/polylith.mdc"                :std/polylith
   "frameworks/kubernetes.mdc"              :std/kubernetes
   "testing/standards.mdc"                  :std/standards
   "workflows/git-branch-management.mdc"    :std/git-branch-management
   "workflows/pre-commit-discipline.mdc"    :std/pre-commit-discipline
   "workflows/git-worktrees.mdc"            :std/git-worktrees
   "workflows/pr-documentation.mdc"         :std/pr-documentation
   "workflows/pr-layering.mdc"              :std/pr-layering
   "workflows/datever.mdc"                  :std/datever
   "project/header-copyright.mdc"           :std/header-copyright
   "meta/rule-format.mdc"                   :std/rule-format
   "index.mdc"                              :std/index})
