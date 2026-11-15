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
(ns ai.miniforge.workflow.resume-restore-test
  "A local resume restores the workspace of the checkpoint it is given.
   Every phase of a task persists to one bundle file, so the bundle's tip
   is the latest phase's commit; a rewind that keeps an earlier phase must
   resume at that phase's commit instead. Real git throughout: the bug is
   in which commit the branch ends up at."
  (:require [ai.miniforge.workflow.isolation-test-support :as isolation]
            [ai.miniforge.workflow.runner-environment :as env]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private missing-commit
  "A well-formed sha no repository here holds."
  (str/join (repeat 40 "0")))

(def ^{:stratum 0} ^:private phases
  "The two phases each scenario runs and persists, in order."
  [:plan :implement])

(defn- ^{:stratum 0} sha
  "REV resolved in DIR, or nil when it names no commit there. Run without
   the `GIT_*` environment, as `isolation/git!` is: a hook's `GIT_DIR`
   would win over `-C`."
  [dir rev]
  (let [hermetic (into {} (remove (fn [[k _]] (str/starts-with? k "GIT_"))) (isolation/launch-env))
        {:keys [exit out]} (shell/sh "git" "-C" (str dir) "rev-parse" "--verify" "--quiet" rev
                                     :env hermetic)]
    (when (zero? exit) (str/trim out))))

(defn- ^{:stratum 0} acquire!
  "A local environment from HOST's seed branch, restoring CHECKPOINT when given."
  [run-id host checkpoint]
  (env/acquire-execution-environment! run-id (cond-> {:repo-path host :branch isolation/host-branch}
                                               checkpoint (assoc :resume-workspace checkpoint))))

(defn- ^{:stratum 0} release! [{:keys [executor environment-id]}]
  (env/release-execution-environment! executor environment-id))

(defn- ^{:stratum 0} persist-phase!
  "Write PHASE's file in the environment's worktree and persist it the way
   the runner does at that phase's boundary. Returns the checkpoint
   `workflow-resume/extract-workspace-checkpoints` reads back from the
   :workspace/persisted event, less its timestamp."
  [run-id {:keys [executor environment-id worktree-path environment-metadata]} phase]
  (spit (io/file worktree-path (str (name phase) ".txt")) (name phase))
  (let [{:keys [data]} (env/persist-workspace-at-phase-boundary!
                        {:execution/executor executor
                         :execution/environment-id environment-id
                         :execution/mode :local
                         :execution/worktree-path worktree-path
                         :execution/environment-metadata environment-metadata
                         :execution/id run-id
                         :execution/current-phase phase}
                        {})]
    (assoc (select-keys data [:branch :bundle-path :commit-sha])
           :persist-tier :worktree :env-id environment-id :phase phase)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-two-phases
  "Run :plan then :implement in one environment of a fresh host repository,
   then call F with the host, that environment and both checkpoints."
  [f]
  (let [root (isolation/temp-root!)]
    (try
      (let [host (isolation/init-host-repo! (str root "/host"))
            run-id (random-uuid)
            environment (acquire! run-id host nil)]
        (f {:host host :run-id run-id :environment environment
            :checkpoints (mapv (partial persist-phase! run-id environment) phases)}))
      (finally (isolation/delete-tree! root)))))

(defn- ^{:stratum 1} resumed-head
  "HEAD of a worktree acquired from CHECKPOINT, released before returning."
  [run-id host checkpoint]
  (let [resumed (acquire! run-id host checkpoint)]
    (try (sha (:worktree-path resumed) "HEAD")
         (finally (release! resumed)))))

(defn- ^{:stratum 1} refused?
  "Whether acquiring from CHECKPOINT is refused as a failed acquisition."
  [run-id host checkpoint]
  (try+ (release! (acquire! run-id host checkpoint)) false
        (catch [:anomaly/category :anomalies.executor/acquisition-failed] _ true)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} rewind-resumes-at-the-kept-phase-commit-test
  (doseq [[attempt resume-id] [["in place" identity] ["as a new attempt" (fn [_] (random-uuid))]]]
    (with-two-phases
      (fn [{:keys [host run-id environment] [plan implement] :checkpoints}]
        (release! environment)
        (testing "both phases persisted to one bundle, whose tip is implement's commit"
          (is (= (:bundle-path plan) (:bundle-path implement)))
          (is (not= (:commit-sha plan) (:commit-sha implement))))
        (testing (str "--from-phase implement keeps plan and resumes at plan's commit, " attempt)
          (let [resumed (acquire! (resume-id run-id) host plan)]
            (try
              (is (= (:commit-sha plan) (sha (:worktree-path resumed) "HEAD")))
              (is (= (:commit-sha plan) (sha host (str "refs/heads/" (:branch plan)))))
              (is (not (.exists (io/file (:worktree-path resumed) "implement.txt"))))
              (finally (release! resumed)))))))))

(deftest ^{:stratum 2} unrestorable-checkpoint-commit-refuses-the-resume-test
  (with-two-phases
    (fn [{:keys [host run-id environment] [plan implement] :checkpoints}]
      (testing "control: a branch a live worktree has checked out at the checkpoint's commit restores"
        (is (= (:commit-sha implement) (resumed-head (random-uuid) host implement))))
      (testing "it cannot be moved back to an earlier checkpoint, so the resume is refused"
        (is (refused? (random-uuid) host plan))
        (is (= (:commit-sha implement) (sha host (str "refs/heads/" (:branch plan))))))
      (release! environment)
      (testing "a checkpoint commit the host repo does not have is refused, not resumed from the tip"
        (is (refused? run-id host (assoc plan :commit-sha missing-commit))))
      (testing "a missing bundle does not matter while the host repo holds the checkpoint's commit"
        (is (= (:commit-sha plan)
               (resumed-head (random-uuid) host (assoc plan :bundle-path (str host "/missing.bundle")))))))))

;; Worktrees and bundles go under a throwaway root, never the checkout the
;; test JVM was launched in.
(use-fixtures :once isolation/with-isolated-host)

(comment ::resume-restore-regressions)
