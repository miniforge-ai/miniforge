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
(ns ai.miniforge.policy-pack.external.matching
  "Pack-applicability / glob-matching helpers for external PR evaluation.
   Split out of `ai.miniforge.policy-pack.external` (rule 210: the
   combined namespace measured 5 real layers, max 3) — diff parsing
   lives in the `diff` sibling and pack selection plus the top-level
   evaluation entry point stay in the parent namespace; the glob
   matching a pack's :file-globs are tested against lives here."
  (:require
   [ai.miniforge.policy-pack.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

;; Pack selection
(defn ^{:stratum 0} path-matches-glob?
  "Test a single path against a single glob pattern."
  [glob-fn glob path]
  (try (glob-fn glob path) (catch Exception _ false)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} files-match-globs?
  "True if any path in `paths` matches any pattern in `globs`."
  [paths globs]
  (some (fn [path]
          (some #(path-matches-glob? registry/glob-matches? % path) globs))
        paths))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pack-applies?
  "True if a pack applies to the given changed files.
   Packs with no :file-globs constraint apply to everything."
  [changed-files pack]
  (let [file-globs (get-in pack [:pack/applies-to :file-globs])]
    (or (not (seq file-globs))
        (files-match-globs? changed-files file-globs))))
