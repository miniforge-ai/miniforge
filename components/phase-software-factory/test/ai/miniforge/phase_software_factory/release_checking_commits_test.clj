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
(ns ai.miniforge.phase-software-factory.release-checking-commits-test
  "Release counts committed work only from commits other than a checking
   phase's boundary commit. A file verify or review left in the worktree
   is committed at their boundary; it must not turn an already-implemented
   task into a release."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.phase.interface.boundary-commit :as boundary-commit]
   [ai.miniforge.phase-software-factory.release-committed-work-test :as committed]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} boundary-commit!
  "Write `path` under `dir` and commit it as `phase`'s boundary commit."
  [dir phase path content]
  (let [file (io/file dir path)]
    (io/make-parents file)
    (spit file content)
    (committed/git! dir "add" "--" path)
    (committed/git! dir "commit" "-q" "-m"
                    (boundary-commit/with-phase-trailer
                      (str (name phase) " phase completed") phase))))

(defn ^{:stratum 0} shell-execute
  "Capsule executor stub that runs each command under `sh -c` in its workdir."
  [_executor _env-id command {:keys [workdir]}]
  (let [{:keys [exit out err]} (shell/sh "sh" "-c" command :dir workdir)]
    {:ok? true :data {:exit-code exit :stdout out :stderr err}}))

(defn ^{:stratum 0} failing-log
  "Capsule executor stub: commits resolve and diff, every `git log` fails."
  [_executor _env-id command _opts]
  (if (str/starts-with? command "git log")
    {:ok? true :data {:exit-code 128 :stdout "" :stderr "fatal: log failed"}}
    {:ok? true :data {:exit-code 0 :stdout committed/some-sha}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-base-repo
  "Call `f` with a repo holding one base commit, and that commit's sha."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "release-checking-"}))]
    (try
      (committed/git! dir "init" "-q")
      (f dir (committed/commit-file! dir "README.md" "# base\n"))
      (finally
        (fs/delete-tree dir)))))

(defn ^{:stratum 1} release-from
  "Run release's :enter for an :already-implemented task based at `base`."
  [dir base]
  (committed/enter-release
   (committed/release-ctx dir :already-implemented {:base-sha base})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} checking-phase-by-products-alone-still-skip-test
  (testing "files committed only at verify and review boundaries are not work:
            an already-implemented task skips"
    (with-base-repo
      (fn [dir base]
        (boundary-commit! dir :verify "coverage/report.json" "{}")
        (boundary-commit! dir :review "deps.lock" "lock")
        (let [{:keys [result prepared]} (release-from dir base)]
          (is (nil? prepared) "release must skip, not publish by-products")
          (is (= :completed (get-in result [:phase :status]))))))))

(deftest ^{:stratum 2} implement-work-is-released-without-by-products-test
  (testing "implement's committed work is released; a later checking phase's
            by-product is left out of the release's files"
    (with-base-repo
      (fn [dir base]
        (boundary-commit! dir :implement "src/été b.clj" "(ns feature)\n")
        (boundary-commit! dir :verify "coverage/report.json" "{}")
        (let [{:keys [prepared]} (release-from dir base)]
          (is (some? prepared))
          (is (= #{"src/été b.clj"} (committed/released-paths prepared))))))))

(deftest ^{:stratum 2} path-a-checking-phase-rewrote-keeps-its-latest-content-test
  (testing "a file implement wrote and verify then rewrote is released with
            the content on the branch"
    (with-base-repo
      (fn [dir base]
        (boundary-commit! dir :implement "src/feature.clj" "(ns feature)\n")
        (boundary-commit! dir :verify "src/feature.clj" "(ns feature) ; formatted\n")
        (let [{:keys [prepared]} (release-from dir base)]
          (is (= "(ns feature) ; formatted\n"
                 (:content (get (committed/released-files prepared) "src/feature.clj")))))))))

(deftest ^{:stratum 2} commits-without-a-phase-trailer-count-test
  (testing "the agent's own commits, and boundary commits made before the
            trailer existed, carry no phase and count as work"
    (with-base-repo
      (fn [dir base]
        (committed/commit-file! dir "src/feature.clj" "(ns feature)\n")
        (let [{:keys [prepared]} (release-from dir base)]
          (is (= #{"src/feature.clj"} (committed/released-paths prepared))))))))

(deftest ^{:stratum 2} capsule-scan-reads-the-phase-trailers-test
  (testing "a governed run's scan, run under sh -c, leaves out a checking
            phase's by-product and releases implement's work"
    (with-base-repo
      (fn [dir base]
        (boundary-commit! dir :implement "src/feature.clj" "(ns feature)\n")
        (boundary-commit! dir :review "deps.lock" "lock")
        (let [{:keys [prepared]} (committed/enter-release
                                  (committed/capsule-ctx dir base shell-execute))]
          (is (= #{"src/feature.clj"} (committed/released-paths prepared))))))))

(deftest ^{:stratum 2} failed-boundary-log-is-raised-not-skipped-test
  (testing "a committed scan whose git log fails raises the git error"
    (let [ex (committed/enter-error (committed/capsule-ctx "/workspace" committed/some-sha failing-log))]
      (is (= :release/git-status-failed (:type (ex-data ex))))
      (is (str/includes? (str (ex-message ex)) "log failed")))))
