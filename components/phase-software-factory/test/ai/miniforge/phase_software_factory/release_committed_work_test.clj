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
   [ai.miniforge.phase-software-factory.release-scan :as release-scan]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} some-sha
  "0123456789abcdef0123456789abcdef01234567")

(defn ^{:stratum 0} git!
  "Run git in `dir` with a fixed identity; return trimmed stdout."
  [dir & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-c" "user.name=t" "-c" "user.email=t@example.invalid"
               "-c" "commit.gpgsign=false" "-c" "protocol.file.allow=always"
               (concat args [:dir dir]))]
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

(defn ^{:stratum 0} released-files
  "{path file} for the files `workflow-state` hands the release executor."
  [workflow-state]
  (->> (:workflow/artifacts workflow-state)
       (mapcat (comp :code/files :artifact/content))
       (into {} (map (juxt :path identity)))))

(defn ^{:stratum 0} failing-diff
  "Capsule executor stub: every commit resolves, every `git diff` fails."
  [_executor _env-id command _opts]
  (if (str/includes? command "rev-parse")
    {:ok? true :data {:exit-code 0 :stdout some-sha}}
    {:ok? true :data {:exit-code 128 :stdout "" :stderr "fatal: bad object"}}))

(defn ^{:stratum 0} enter-error
  "Run release's :enter on `ctx` and return the ExceptionInfo it throws,
   or nil when it returns."
  [ctx]
  (try
    ((:enter (phase/get-phase-interceptor {:phase :release})) ctx)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} capsule-ctx
  "A governed release context on a clean capsule worktree at `worktree`
   whose environment base is `base-sha`; `execute-fn` answers every git
   command but the clean `git status`."
  [worktree base-sha execute-fn]
  (assoc (release-ctx worktree :already-implemented {:base-sha base-sha})
         :execution/mode           :governed
         :execution/executor       :mock-executor
         :execution/environment-id "env-1"
         :execution/execute-fn
         (fn [executor env-id command opts]
           (if (str/starts-with? command "git status")
             {:ok? true :data {:exit-code 0 :stdout ""}}
             (execute-fn executor env-id command opts)))))

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
   commit adds `path`, and with the two commits' shas. The worktree is
   clean."
  [path f]
  (let [dir (str (fs/create-temp-dir {:prefix "release-committed-"}))]
    (try
      (git! dir "init" "-q")
      (let [base (commit-file! dir "README.md" "# base\n")
            head (commit-file! dir path "(ns feature)\n")]
        (is (= "" (git! dir "status" "--porcelain")) "fixture: worktree is clean")
        (f dir base head))
      (finally
        (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 3

(deftest ^{:stratum 3} committed-work-is-released-not-skipped-test
  (testing ":already-implemented on a clean worktree whose branch committed
            work beyond its base releases that work"
    (with-task-branch "src/feature.clj"
      (fn [dir base _head]
        (let [{:keys [prepared]} (enter-release
                                  (release-ctx dir :already-implemented {:base-sha base}))]
          (is (some? prepared) "release must prepare, not skip")
          (is (= #{"src/feature.clj"} (released-paths prepared))))))))

(deftest ^{:stratum 3} resumed-run-measures-from-the-recorded-task-base-test
  (testing "a resumed run whose environment base is already past the work
            measures from the base the first implement attempt recorded"
    (with-task-branch "src/feature.clj"
      (fn [dir base head]
        (let [ctx (assoc-in (release-ctx dir nil {:base-sha head})
                            [:execution/phase-results :implement :artifact :code/base-refs]
                            [(str base "...HEAD") base "origin/main"])
              {:keys [prepared]} (enter-release ctx)]
          (is (some? prepared))
          (is (= #{"src/feature.clj"} (released-paths prepared))))))))

(deftest ^{:stratum 3} branch-with-nothing-beyond-base-still-skips-test
  (testing "a clean worktree at its base commit skips as before"
    (with-task-branch "src/feature.clj"
      (fn [dir _base head]
        (let [{:keys [result prepared]} (enter-release
                                         (release-ctx dir :already-implemented {:base-sha head}))]
          (is (nil? prepared))
          (is (= :completed (get-in result [:phase :status]))))))))

(deftest ^{:stratum 3} committed-path-git-would-quote-is-released-test
  (testing "a committed file whose name git quotes without -z is read and released"
    (with-task-branch "src/\u00e9t\u00e9 b.clj"
      (fn [dir base _head]
        (let [{:keys [prepared]} (enter-release
                                  (release-ctx dir :already-implemented {:base-sha base}))]
          (is (= #{"src/\u00e9t\u00e9 b.clj"} (released-paths prepared))))))))

(deftest ^{:stratum 3} base-the-clone-never-fetched-is-passed-over-test
  (testing "a base sha that names no commit here is not diffed, and a clean
            worktree with no other base skips as before"
    (with-task-branch "src/feature.clj"
      (fn [dir _base _head]
        (let [{:keys [result prepared]} (enter-release
                                         (release-ctx dir :already-implemented {:base-sha some-sha}))]
          (is (nil? prepared))
          (is (= :completed (get-in result [:phase :status]))))))))

(deftest ^{:stratum 3} failed-committed-diff-is-raised-not-skipped-test
  (testing "a committed scan whose git diff fails raises the git error"
    (let [ex (enter-error (capsule-ctx "/workspace" some-sha failing-diff))]
      (is (= :release/git-status-failed (:type (ex-data ex))))
      (is (str/includes? (str (ex-message ex)) "bad object")))))

(deftest ^{:stratum 3} capsule-executor-error-is-raised-not-skipped-test
  (testing "a committed scan the capsule executor cannot run raises its error"
    (let [ex (enter-error
              (capsule-ctx "/workspace" some-sha
                           (fn [_executor _env-id _command _opts]
                             {:ok? false :error {:code :exec-failed
                                                 :message "container not running"}})))]
      (is (= :release/git-status-failed (:type (ex-data ex))))
      (is (str/includes? (str (ex-message ex)) "container not running")))))

(deftest ^{:stratum 3} recorded-files-carry-the-release-when-the-scan-fails-test
  (testing "when the committed scan fails but implement recorded files,
            release goes ahead with the recorded files: the branch's commits
            ship either way, and the files only feed the PR metadata"
    (let [dir (str (fs/create-temp-dir {:prefix "release-recorded-"}))]
      (try
        (spit (io/file dir "a.clj") "(ns a)")
        (let [{:keys [prepared]} (enter-release
                                  (assoc-in (capsule-ctx dir some-sha failing-diff)
                                            [:execution/phase-results :implement :artifact]
                                            {:code/file-paths ["a.clj"] :code/file-actions [:create]}))]
          (is (= #{"a.clj"} (released-paths prepared))))
        (finally
          (fs/delete-tree dir))))))

(deftest ^{:stratum 3} capsule-scan-runs-in-the-capsule-checkout-test
  (testing "a governed run scans the capsule's own checkout, not the host
            worktree path, and keeps a SHA-256 base sha"
    (let [calls      (atom [])
          sha256     (apply str (repeat 64 "a"))
          execute-fn (fn [_executor _env-id command {:keys [workdir]}]
                       (swap! calls conj [command workdir])
                       {:ok? true :data {:exit-code 0 :stdout ""}})
          ctx        (assoc (release-ctx "/host/worktree" :already-implemented {:base-sha sha256})
                            :execution/mode            :governed
                            :execution/executor        :mock-executor
                            :execution/environment-id  "env-1"
                            :execution/execute-fn      execute-fn
                            :execution/capsule-workdir "/workspace")]
      (enter-release ctx)
      (is (some #(str/starts-with? (first %) "git status") @calls))
      (is (every? #(= "/workspace" (second %)) @calls))
      (is (some #(str/includes? (first %) sha256) @calls)))))

(deftest ^{:stratum 3} exit-one-with-output-is-a-failure-not-a-missing-commit-test
  (testing "rev-parse exiting 1 with an error (a capsule whose container is
            gone) raises instead of reading as a missing commit and skipping"
    (let [ex (enter-error
              (capsule-ctx "/workspace" some-sha
                           (fn [_executor _env-id _command _opts]
                             {:ok? true :data {:exit-code 1 :stdout ""
                                               :stderr "Error: No such container: env-1"}})))]
      (is (= :release/git-status-failed (:type (ex-data ex))))
      (is (str/includes? (str (ex-message ex)) "No such container")))))

(deftest ^{:stratum 3} host-reader-refuses-a-path-outside-the-worktree-test
  (testing "the host reader does not read a path whose target leaves the worktree"
    (with-task-branch "src/feature.clj"
      (fn [dir _base _head]
        (let [{:keys [content failure]} ((release-scan/host-reader dir) "../outside.clj")]
          (is (nil? content))
          (is (str/includes? (:detail failure) "outside the worktree")))))))

(deftest ^{:stratum 3} committed-symlink-is-released-without-its-target-test
  (testing "a committed symlink to a file outside the worktree is released by
            path, and the target's content never enters the release"
    (let [outside (str (fs/create-temp-file {:prefix "release-secret-"}))]
      (try
        (spit outside "SECRET")
        (with-task-branch "src/feature.clj"
          (fn [dir base _head]
            (fs/create-sym-link (fs/path dir "link") outside)
            (git! dir "add" "--" "link")
            (git! dir "commit" "-q" "-m" "add link")
            (let [{:keys [prepared]} (enter-release
                                      (release-ctx dir :already-implemented {:base-sha base}))
                  files (released-files prepared)]
              (is (= #{"src/feature.clj" "link"} (set (keys files))))
              (is (= "" (get-in files ["link" :content])))
              (is (not-any? #(str/includes? (str (:content %)) "SECRET") (vals files))))))
        (finally
          (fs/delete outside))))))

(deftest ^{:stratum 3} committed-submodule-is-released-not-raised-test
  (testing "a committed submodule is released by path, not read as a file"
    (let [sub (str (fs/create-temp-dir {:prefix "release-sub-"}))]
      (try
        (git! sub "init" "-q")
        (git! sub "commit" "-q" "--allow-empty" "-m" "sub")
        (with-task-branch "src/feature.clj"
          (fn [dir base _head]
            (git! dir "submodule" "add" "-q" sub "mod")
            (git! dir "commit" "-q" "-m" "add submodule")
            (let [{:keys [prepared]} (enter-release
                                      (release-ctx dir :already-implemented {:base-sha base}))
                  files (released-files prepared)]
              (is (contains? files "mod"))
              (is (= "" (get-in files ["mod" :content]))))))
        (finally
          (fs/delete-tree sub))))))

(deftest ^{:stratum 3} reverted-work-measured-from-the-recorded-base-skips-test
  (testing "the recorded base decides even when its diff is empty: work a
            repair reverted is not counted against a newer environment base"
    (with-task-branch "src/feature.clj"
      (fn [dir base head]
        (git! dir "rm" "-q" "--" "src/feature.clj")
        (git! dir "commit" "-q" "-m" "revert feature")
        (let [ctx (assoc-in (release-ctx dir :already-implemented {:base-sha head})
                            [:execution/phase-results :implement :artifact :code/base-refs]
                            [base])
              {:keys [result prepared]} (enter-release ctx)]
          (is (nil? prepared))
          (is (= :completed (get-in result [:phase :status]))))))))

(deftest ^{:stratum 3} governed-success-release-scans-the-capsule-checkout-test
  (testing "a governed release with implement :success lists its dirty files
            in the capsule's checkout, not the host worktree path"
    (let [workdirs   (atom #{})
          execute-fn (fn [_executor _env-id command {:keys [workdir]}]
                       (swap! workdirs conj workdir)
                       (if (str/starts-with? command "git status")
                         {:ok? true :data {:exit-code 0 :stdout "?? a.clj\u0000"}}
                         {:ok? true :data {:exit-code 0 :stdout "(ns a)"}}))
          ctx        (assoc (release-ctx "/host/worktree" :success {})
                            :execution/mode            :governed
                            :execution/executor        :mock-executor
                            :execution/environment-id  "env-1"
                            :execution/execute-fn      execute-fn
                            :execution/capsule-workdir "/workspace")
          {:keys [prepared]} (enter-release ctx)]
      (is (= #{"/workspace"} @workdirs))
      (is (= #{"a.clj"} (released-paths prepared))))))

(deftest ^{:stratum 3} dirty-symlink-target-never-enters-the-release-test
  (testing "an untracked symlink to a file outside the worktree, the only
            change, is released by path and never read, on the host or
            through the capsule reader under sh -c"
    (let [outside (str (fs/create-temp-file {:prefix "release-secret-"}))
          sh-exec (fn [_executor _env-id command {:keys [workdir]}]
                    (let [{:keys [exit out err]} (shell/sh "sh" "-c" command :dir workdir)]
                      {:ok? true :data {:exit-code exit :stdout out :stderr err}}))]
      (try
        (spit outside "SECRET")
        (with-task-branch "src/feature.clj"
          (fn [dir _base _head]
            (fs/create-sym-link (fs/path dir "link") outside)
            (let [host    (enter-release (release-ctx dir :success {}))
                  capsule (enter-release
                           (assoc (release-ctx dir :success {})
                                  :execution/mode            :governed
                                  :execution/executor        :mock-executor
                                  :execution/environment-id  "env-1"
                                  :execution/execute-fn      sh-exec
                                  :execution/capsule-workdir dir))]
              (doseq [{:keys [prepared]} [host capsule]]
                (is (= "" (get-in (released-files prepared) ["link" :content])))
                (is (not-any? #(str/includes? (str (:content %)) "SECRET")
                              (vals (released-files prepared))))))))
        (finally
          (fs/delete outside))))))

