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
  "Composition and classification of provider-backed merge-readiness checks."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} enabled-checks
  [{:keys [check-ci check-review check-branch check-threads]}
   worktree-path pr-number policy]
  {:ci (when (:require-ci-green? policy)
         (check-ci worktree-path pr-number))
   :review (when (:require-approvals? policy)
             (check-review worktree-path pr-number
                           (:required-approvals policy)))
   :branch (when (:require-branch-up-to-date? policy)
             (check-branch worktree-path pr-number))
   :threads (when (:require-no-unresolved-threads? policy)
              (check-threads worktree-path pr-number))})

(defn- ^{:stratum 0} blocking-reasons
  [{:keys [ci review branch threads]}]
  (cond-> []
    (dag/err? ci)
    (conj :ci-status-unavailable)

    (and (dag/ok? ci) (not (:ci-green? (:data ci))))
    (conj :ci-not-green)

    (dag/err? review)
    (conj :review-status-unavailable)

    (and (dag/ok? review) (not (:approved? (:data review))))
    (conj :not-approved)

    (dag/err? branch)
    (conj :branch-status-unavailable)

    (and (dag/ok? branch) (not (:up-to-date? (:data branch))))
    (conj :branch-not-up-to-date)

    (dag/err? threads)
    (conj :thread-status-unavailable)

    (and (dag/ok? threads) (:has-unresolved? (:data threads)))
    (conj :unresolved-threads)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} evaluate
  "Run enabled checks and return every blocking reason."
  [operations worktree-path pr-number policy]
  (let [checks (enabled-checks operations worktree-path pr-number policy)
        blocking (blocking-reasons checks)]
    {:ready? (empty? blocking)
     :checks checks
     :blocking blocking}))
