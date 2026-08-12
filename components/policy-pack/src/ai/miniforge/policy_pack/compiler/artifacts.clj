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
(ns ai.miniforge.policy-pack.compiler.artifacts
  "Normalize N4 check input into the artifact maps detectors accept.

   Split out of `ai.miniforge.policy-pack.compiler` (rule 210: the
   combined namespace measured 8 real layers, max 3). This namespace
   holds the artifact-normalization chain — `code-files` through
   `artifact-inputs` sat at the bottom of that chain and, kept
   together, are 3 layers on their own; extracting them lets the
   parent and its other siblings (`compiler.check`, `compiler.rule`)
   each land under budget too.

   Layer 0: code-files, code-files-present?, file-entry->artifact
   Layer 1: normalize-artifacts
   Layer 2: artifact-inputs")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} code-files
  [artifacts]
  (or (get artifacts :code/files)
      (get artifacts :files)))

(defn- ^{:stratum 0} code-files-present?
  [artifacts]
  (or (contains? artifacts :code/files)
      (contains? artifacts :files)))

(defn- ^{:stratum 0} file-entry->artifact
  [file-entry]
  (let [path    (or (get file-entry :artifact/path)
                    (get file-entry :path))
        content (or (get file-entry :artifact/content)
                    (get file-entry :content))]
    (cond-> file-entry
      path    (assoc :artifact/path path)
      content (assoc :artifact/content content))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} normalize-artifacts
  "Normalize N4 check input into artifact maps that existing detectors accept."
  [artifacts]
  (cond
    (sequential? artifacts) (mapv file-entry->artifact artifacts)
    (code-files-present? artifacts) (mapv file-entry->artifact (code-files artifacts))
    (map? artifacts) [(file-entry->artifact artifacts)]
    :else []))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} artifact-inputs
  [detector artifacts]
  (let [normalized (normalize-artifacts artifacts)]
    (if (and (= :semantic detector) (seq normalized))
      [(first normalized)]
      normalized)))
