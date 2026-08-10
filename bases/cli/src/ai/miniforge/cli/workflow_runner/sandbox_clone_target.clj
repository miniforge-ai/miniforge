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
(ns ai.miniforge.cli.workflow-runner.sandbox-clone-target
  "Which repository and branch a sandbox container should check out.
   Resolves the clone target from the work spec, then the enriched spec's
   context, then the git remote of the spec's source directory, then the
   current working directory's remote. Split out of
   `ai.miniforge.cli.workflow-runner.sandbox` (rule 210: the parent
   namespace measured 4 real layers, max 3)."
  (:require
   [clojure.string :as str]
   [babashka.process :as p]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.
(defn- ^{:stratum 0} git-remote-url
  "Get git remote origin URL from a directory. Returns nil on failure."
  [dir]
  (try
    (let [result (p/shell {:out :string :err :string :continue true :dir dir}
                          "git" "remote" "get-url" "origin")]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

(defn ^{:stratum 0} infer-branch [spec enriched-spec]
  (or (:spec/branch spec)
      (get-in enriched-spec [:spec/context :git-branch])
      "main"))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.
(defn ^{:stratum 1} infer-repo-url [spec enriched-spec]
  (or (:spec/repo-url spec)
      (get-in enriched-spec [:spec/context :repo-url])
      ;; If spec came from a file in a different repo, use that repo's remote.
      ;; :spec/source-dir is set by the run command from the spec file's parent.
      (when-let [source-dir (:spec/source-dir spec)]
        (git-remote-url source-dir))
      ;; Final fallback: cwd's repo
      (git-remote-url ".")))
