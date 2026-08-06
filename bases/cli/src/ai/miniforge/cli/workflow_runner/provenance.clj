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
(ns ai.miniforge.cli.workflow-runner.provenance
  "Startup provenance banners: where the runtime is executing from
   (source root, worktree, git state) and which backend CLI it resolved
   (path, version). Split out of `ai.miniforge.cli.workflow-runner`
   (rule 210: the parent namespace measured 10 real layers, max 3; each
   concern moves to its own layer-coherent file)."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display :as display]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} print-colored-lines!
  [quiet lines]
  (when-not quiet
    (doseq [[color line] lines]
      (println (display/colorize color line)))))

(defn- ^{:stratum 0} runtime-provenance-lines
  [context]
  (concat
   [[:cyan (messages/t :workflow-runner/runtime-source
                       {:path (:source-root context)})]
    [:cyan (messages/t :workflow-runner/runtime-worktree
                       {:path (:worktree-path context)})]]
   (when-let [branch (:git-branch context)]
     [[:cyan (messages/t :workflow-runner/runtime-branch {:branch branch})]])
   (when-let [commit (:git-commit context)]
     [[:cyan (messages/t :workflow-runner/runtime-commit {:commit commit})]])
   (when-let [upstream (:git-upstream context)]
     [[:cyan (messages/t :workflow-runner/runtime-upstream {:upstream upstream})]])
   (when (:git-detached? context)
     [[:yellow (messages/t :workflow-runner/runtime-detached-warning)]])
   (when (:git-dirty? context)
     [[:yellow (messages/t :workflow-runner/runtime-dirty-warning)]])))

(defn- ^{:stratum 0} backend-provenance-lines
  [{:keys [backend cmd-path cmd-version]}]
  (concat
   [[:cyan (messages/t :workflow-runner/backend-label
                       {:backend (name backend)})]]
   (when cmd-path
     [[:cyan (messages/t :workflow-runner/backend-path {:path cmd-path})]])
   (when cmd-version
     [[:cyan (messages/t :workflow-runner/backend-version {:version cmd-version})]])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} print-runtime-provenance!
  [quiet context]
  (print-colored-lines! quiet (runtime-provenance-lines context)))

(defn ^{:stratum 1} print-backend-provenance!
  [quiet {:keys [backend cmd-path cmd-version]}]
  (print-colored-lines! quiet (backend-provenance-lines {:backend backend
                                                         :cmd-path cmd-path
                                                         :cmd-version cmd-version})))
