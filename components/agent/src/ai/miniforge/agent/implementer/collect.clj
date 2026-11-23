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
(ns ai.miniforge.agent.implementer.collect
  "Collection of the files an implementer session wrote, and
   re-normalization of a session's result channels."
  (:require
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.agent.implementer.code :as impl-code]
   [ai.miniforge.agent.implementer.session :as impl-session]
   [ai.miniforge.agent.result-boundary :as result-boundary]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} collect-promoted-artifact
  "Collect code files from the current promoted worktree/container state."
  [context session-mode working-dir]
  (let [base-refs (impl-session/base-ref-candidates context)]
    (if (= :capsule session-mode)
      (when-let [exec! (:execution/execute-fn context)]
        (file-artifacts/collect-worktree-files-via-executor
         exec!
         (:execution/executor context)
         (:execution/environment-id context)
         working-dir
         base-refs))
      (file-artifacts/collect-worktree-files working-dir base-refs))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} collect-session-artifact
  "Collect a file artifact from the promoted or written working tree."
  [context session-mode working-dir pre-session-snapshot]
  (or (collect-promoted-artifact context session-mode working-dir)
      (if (= :capsule session-mode)
        (when-let [exec! (:execution/execute-fn context)]
          (file-artifacts/collect-written-files-via-executor
           pre-session-snapshot
           exec!
           (:execution/executor context)
           (:execution/environment-id context)
           working-dir))
        (file-artifacts/collect-written-files pre-session-snapshot
                                              working-dir))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} normalize-implementer-result
  "Re-normalize a session's raw channels into the implementer result shape,
   re-collecting the file artifact from what was written this turn. Used by the
   submission-recovery turn (the main turn normalizes inline so it can also
   surface the file artifact for its fallback log)."
  [{:keys [llm-result artifact worktree-artifacts pre-session-snapshot session-mode]}
   context working-dir]
  (let [file-artifact (collect-session-artifact context session-mode working-dir
                                                pre-session-snapshot)]
    (result-boundary/normalize-llm-result
     {:role :implement
      :response llm-result
      :worktree-artifacts worktree-artifacts
      :artifact artifact
      :fallback-artifact file-artifact
      :parse-response impl-code/parse-code-response
      :derive-artifact impl-code/code-from-blocks})))
