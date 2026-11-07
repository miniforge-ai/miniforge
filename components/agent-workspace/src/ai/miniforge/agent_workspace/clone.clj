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
(ns ai.miniforge.agent-workspace.clone
  "Clone every repository of a request into one workspace inside the
   leased environment, each on its own task branch from its base, and
   record the base it started from."
  (:require
   [ai.miniforge.agent-workspace.git :as git]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} checkout!
  "Clone one repository and start its task branch; the Checkout or the
   first failure."
  [wc acting lease root repo]
  (let [path (git/repo-path root (:repo/id repo))
        branch (git/task-branch (:lease/task-id lease))]
    (anomaly/let-ok [_ (git/git! wc acting lease (git/clone-argv (git/repo-url wc (:repo/id repo)) path))
                     _ (git/git! wc acting lease (git/branch-argv path branch (:repo/base-ref repo)))
                     head (git/git! wc acting lease (git/head-argv path))]
      {:repo/id (:repo/id repo)
       :repo/path path
       :repo/base-sha (git/sha-of head)
       :repo/task-branch branch})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} add-checkout
  [wc acting lease root checkouts repo]
  (let [checkout (checkout! wc acting lease root repo)]
    (if (anomaly/anomaly? checkout) (reduced checkout) (conj checkouts checkout))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} clone!
  "Every repository's Checkout in request order, or the first failure."
  [wc acting lease request]
  (reduce #(add-checkout wc acting lease (:workspace/root request) %1 %2) [] (:workspace/repos request)))
