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
(ns ai.miniforge.phase-software-factory.release-resume-base-test
  "A resumed run whose earlier attempt recorded no task base still releases
   the work that attempt committed. implement then records the resume's
   own candidates (`base-ref-candidates`): `X...HEAD` ranges from the
   original commit and branch, then the resumed environment's base commit.
   That commit is the checkpoint the resume restored, which already holds
   the earlier commits, so measuring from it found nothing and the release
   was skipped (#2264 review)."
  (:require
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-software-factory.implement :as implement]
   [ai.miniforge.phase-software-factory.release]
   [ai.miniforge.release-executor.interface :as release-executor]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git!
  "Run git in `dir` with a fixed identity and without the repository
   variables; return trimmed stdout."
  [dir & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-c" "user.name=t" "-c" "user.email=t@example.invalid"
               "-c" "commit.gpgsign=false"
               (concat args [:dir dir
                             :env (release-executor/without-repository-env (System/getenv))]))]
    (is (zero? exit) (str "git " (str/join " " args) ": " err))
    (str/trim out)))

(defn ^{:stratum 0} resumed-ctx
  "The release context of a resume on `worktree` whose environment base is
   the checkpoint `checkpoint`, for a run the CLI started on `start-sha` of
   branch main, with no task base recorded before this attempt. The task
   base refs are what implement recorded for it."
  [worktree checkpoint start-sha]
  (let [ctx {:execution/id                   (random-uuid)
             :execution/input                {:description "Test release"
                                              :title       "Add feature"
                                              :context     {:git-commit start-sha
                                                            :git-branch "main"}}
             :execution/metrics              {:tokens 0 :duration-ms 0}
             :execution/phase-results        {:implement {:result {:status :already-implemented}}}
             :execution/environment-metadata {:base-sha    checkpoint
                                              :base-branch "task-restored"}
             :execution/opts                 {:resume-workspace {:branch "task-restored"}}
             :worktree-path                  worktree
             :phase-config                   {:phase :release}}]
    (assoc ctx :execution/task-base-refs (#'implement/base-ref-candidates ctx))))

(defn ^{:stratum 0} enter-release
  "Run release's :enter on `ctx` with prepare-release stubbed; return the
   workflow state prepare received, or nil when release skipped."
  [ctx]
  (let [seen (atom nil)]
    (with-redefs [release-executor/prepare-release
                  (fn [workflow-state _exec-context _opts]
                    (reset! seen workflow-state)
                    {:success? true :artifacts [] :metrics {:files-written 0}})]
      ((:enter (phase/get-phase-interceptor {:phase :release})) ctx)
      @seen)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-resumed-checkpoint
  "Call `f` with a clean worktree on branch task-restored whose HEAD (the
   checkpoint) holds an earlier attempt's commit beyond main, and with the
   start and checkpoint shas."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "release-resume-"}))
        commit-file! (fn [path content]
                       (let [file (io/file dir path)]
                         (io/make-parents file)
                         (spit file content)
                         (git! dir "add" "--" path)
                         (git! dir "commit" "-q" "-m" (str "add " path))
                         (git! dir "rev-parse" "HEAD")))]
    (try
      (git! dir "init" "-q" "-b" "main")
      (let [start      (commit-file! "README.md" "# base\n")
            _          (git! dir "checkout" "-q" "-b" "task-restored")
            checkpoint (commit-file! "src/feature.clj" "(ns feature)\n")]
        (is (= "" (git! dir "status" "--porcelain")) "fixture: worktree is clean")
        (f dir start checkpoint))
      (finally
        (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} resume-without-recorded-base-measures-from-the-original-base-test
  (with-resumed-checkpoint
    (fn [dir start checkpoint]
      (let [ctx (resumed-ctx dir checkpoint start)]
        (testing "the recorded refs have the producer's resume shape"
          (is (= [(str start "...HEAD") checkpoint]
                 (filterv #(or (str/starts-with? % start) (= % checkpoint))
                          (:execution/task-base-refs ctx)))))
        (testing "the earlier attempt's committed work is released, not skipped"
          (let [prepared (enter-release ctx)]
            (is (some? prepared) "release skipped")
            (is (= #{"src/feature.clj"}
                   (->> (:workflow/artifacts prepared)
                        (mapcat (comp :code/files :artifact/content))
                        (map :path)
                        set)))))))))
