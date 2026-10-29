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
(ns ai.miniforge.workflow.governed-environment-test
  "A governed environment keeps the capsule's own checkout path beside the
   host worktree path."
  (:require [ai.miniforge.workflow.governed-environment :as governed]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ports
  "Fake acquisition ports: a host worktree and a capsule at /workspace."
  {:create-registry (constantly :registry)
   :select-exec     (fn [& _] ::worktree)
   :acquire-env!    (fn [executor _task-id _config]
                      (if (= ::worktree executor)
                        {:environment-id "wt-1" :workdir "/host/worktree"}
                        {:environment-id "cap-1" :workdir "/workspace"}))
   :result-ok?      (constantly true)
   :result-unwrap   identity})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} the-capsule-workdir-is-kept-test
  (testing "capsule-side git runs in the capsule's checkout, not the host path"
    (let [env (governed/acquire! ::capsule (random-uuid) :governed {} ports)]
      (is (= "/host/worktree" (:worktree-path env)))
      (is (= "/workspace" (:capsule-workdir env))))))
