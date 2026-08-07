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
(ns ai.miniforge.pr-lifecycle.merge-readiness
  "Pure composition of provider-backed merge-readiness checks."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} evaluate
  "Run enabled checks and return every blocking reason."
  [{:keys [check-ci check-review check-branch check-threads]}
   worktree-path pr-number policy]
  (let [ci-check (when (:require-ci-green? policy)
                   (check-ci worktree-path pr-number))
        review-check (when (:require-approvals? policy)
                       (check-review worktree-path pr-number
                                     (:required-approvals policy)))
        branch-check (when (:require-branch-up-to-date? policy)
                       (check-branch worktree-path pr-number))
        thread-check (when (:require-no-unresolved-threads? policy)
                       (check-threads worktree-path pr-number))
        checks {:ci ci-check
                :review review-check
                :branch branch-check
                :threads thread-check}
        blocking (cond-> []
                   (and ci-check (not (:ci-green? (:data ci-check))))
                   (conj :ci-not-green)

                   (and review-check (not (:approved? (:data review-check))))
                   (conj :not-approved)

                   (and branch-check (not (:up-to-date? (:data branch-check))))
                   (conj :branch-not-up-to-date)

                   (and thread-check (dag/err? thread-check))
                   (conj :thread-status-unavailable)

                   (and (dag/ok? thread-check)
                        (:has-unresolved? (:data thread-check)))
                   (conj :unresolved-threads))]
    {:ready? (empty? blocking)
     :checks checks
     :blocking blocking}))
