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
(ns ai.miniforge.gate.policy-pack.artifact
  "Resolves the artifact the policy gate evaluates, and the per-file inputs
   its rules check."
  (:require
   [ai.miniforge.gate.policy-pack.worktree :as worktree]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} artifact-changed-files
  "The changed files [{:path :content}] this gate evaluates, taken from the
   resolved policy artifact (its code files, or a single content artifact).
   This is the changed-files scope handed to the judge."
  [artifact]
  (cond
    (seq (:code/files artifact))
    (mapv (fn [f] {:path    (or (:path f) (:artifact/path f))
                   :content (or (:content f) (:artifact/content f))})
          (:code/files artifact))

    (or (:artifact/path artifact) (:path artifact))
    [{:path    (or (:artifact/path artifact) (:path artifact))
      :content (or (:artifact/content artifact) (:content artifact))}]

    :else []))

(defn- ^{:stratum 0} artifact-has-code?
  [artifact]
  (and (map? artifact)
       (or (seq (:code/files artifact))
           (contains? artifact :artifact/content)
           (contains? artifact :content))))

(defn- ^{:stratum 0} implement-artifact
  [ctx]
  (get-in ctx [:execution/phase-results :implement :artifact]))

(defn- ^{:stratum 0} file-entry->artifact
  [file-entry]
  (let [path    (or (:artifact/path file-entry) (:path file-entry))
        content (or (:artifact/content file-entry) (:content file-entry))]
    (cond-> file-entry
      path    (assoc :artifact/path path)
      content (assoc :artifact/content content))))

(defn- ^{:stratum 0} rehydrate-code-files
  [artifact worktree-path]
  (when-let [paths (seq (:code/file-paths artifact))]
    (let [actions (get artifact :code/file-actions [])
          files   (mapv (fn [idx path]
                          (worktree/code-file-entry worktree-path path
                                           (or (nth actions idx nil) :modify)))
                        (range)
                        paths)]
      (assoc artifact :code/files files))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} artifact-inputs
  [artifact]
  (if-let [files (seq (:code/files artifact))]
    (mapv file-entry->artifact files)
    [(file-entry->artifact artifact)]))

(defn ^{:stratum 1} policy-artifact
  "Resolve the artifact that pack-derived policy should evaluate.

   Verify emits test metadata and review emits a verdict, but policy rules
   target the code-under-review. Prefer an explicit code artifact when present;
   otherwise use the implement phase artifact, rehydrating paths-only metadata
   from the execution worktree when available."
  [artifact ctx]
  (let [impl-artifact (implement-artifact ctx)
        worktree-path (:execution/worktree-path ctx)]
    (cond
      (artifact-has-code? artifact)
      artifact

      (and worktree-path (seq (:code/file-paths impl-artifact)))
      (rehydrate-code-files impl-artifact worktree-path)

      (artifact-has-code? impl-artifact)
      impl-artifact

      :else
      artifact)))
