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
(ns ai.miniforge.phase-software-factory.release-committed-work-test
  "Release skips only when the task branch holds nothing to release. An
   implement that reports :already-implemented after an earlier iteration
   committed its work leaves a clean worktree; release used to read that
   as nothing to release, skip, and drop the committed work. A resumed run
   measures from the task base its first implement attempt recorded."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-software-factory.release]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git!
  "Run git in `dir` with a fixed identity; return trimmed stdout."
  [dir & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-c" "user.name=t" "-c" "user.email=t@example.invalid"
               "-c" "commit.gpgsign=false" (concat args [:dir dir]))]
    (is (zero? exit) (str "git " (str/join " " args) ": " err))
    (str/trim out)))

(defn ^{:stratum 0} release-ctx
  [worktree impl-status environment-metadata]
  {:execution/id                   (random-uuid)
   :execution/input                {:description "Test release" :title "Add feature"}
   :execution/metrics              {:tokens 0 :duration-ms 0}
   :execution/phase-results        {:implement {:result {:status impl-status}}}
   :execution/environment-metadata environment-metadata
   :worktree-path                  worktree
   :phase-config                   {:phase :release}})

(defn ^{:stratum 0} enter-release
  "Run release's :enter on `ctx` with prepare-release stubbed. Returns
   {:result enter's ctx :prepared the workflow state prepare received, or nil}."
  [ctx]
  (let [seen (atom nil)]
    (with-redefs [release-executor/prepare-release
                  (fn [workflow-state _exec-context _opts]
                    (reset! seen workflow-state)
                    {:success? true :artifacts [] :metrics {:files-written 0}})]
      {:result   ((:enter (phase/get-phase-interceptor {:phase :release})) ctx)
       :prepared @seen})))

(defn ^{:stratum 0} released-paths
  [workflow-state]
  (->> (:workflow/artifacts workflow-state)
       (mapcat (comp :code/files :artifact/content))
       (map :path)
       set))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} commit-file!
  "Write `path` under `dir` and commit it; return the new HEAD."
  [dir path content]
  (let [file (io/file dir path)]
    (io/make-parents file)
    (spit file content)
    (git! dir "add" "--" path)
    (git! dir "commit" "-q" "-m" (str "add " path))
    (git! dir "rev-parse" "HEAD")))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-task-branch
  "Call `f` with a repo whose base commit holds README.md and whose task
   commit adds src/feature.clj, and with the two commits' shas. The
   worktree is clean."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "release-committed-"}))]
    (try
      (git! dir "init" "-q")
      (let [base (commit-file! dir "README.md" "# base\n")
            head (commit-file! dir "src/feature.clj" "(ns feature)\n")]
        (is (= "" (git! dir "status" "--porcelain")) "fixture: worktree is clean")
        (f dir base head))
      (finally
        (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 3

(deftest ^{:stratum 3} committed-work-is-released-not-skipped-test
  (testing ":already-implemented on a clean worktree whose branch committed
            work beyond its base releases that work"
    (with-task-branch
      (fn [dir base _head]
        (let [{:keys [prepared]} (enter-release
                                  (release-ctx dir :already-implemented {:base-sha base}))]
          (is (some? prepared) "release must prepare, not skip")
          (is (= #{"src/feature.clj"} (released-paths prepared))))))))

(deftest ^{:stratum 3} resumed-run-measures-from-the-recorded-task-base-test
  (testing "a resumed run whose environment base is already past the work
            measures from the base the first implement attempt recorded"
    (with-task-branch
      (fn [dir base head]
        (let [ctx (assoc-in (release-ctx dir nil {:base-sha head})
                            [:execution/phase-results :implement :artifact :code/base-refs]
                            [(str base "...HEAD") base "origin/main"])
              {:keys [prepared]} (enter-release ctx)]
          (is (some? prepared))
          (is (= #{"src/feature.clj"} (released-paths prepared))))))))

(deftest ^{:stratum 3} branch-with-nothing-beyond-base-still-skips-test
  (testing "a clean worktree at its base commit skips as before"
    (with-task-branch
      (fn [dir _base head]
        (let [{:keys [result prepared]} (enter-release
                                         (release-ctx dir :already-implemented {:base-sha head}))]
          (is (nil? prepared))
          (is (= :completed (get-in result [:phase :status]))))))))
