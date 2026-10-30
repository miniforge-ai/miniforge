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
(ns ai.miniforge.phase-software-factory.release-file-scan-test
  "Release's working-tree scan must report a failed `git status` as a
   failure, not as an empty diff. Before this, a non-zero exit, an
   executor error, or a throw all came back as `[]`, release raised the
   terminal `:release/zero-files`, and git's error was lost."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase-software-factory.release]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} with-non-repo-dir
  "Call `f` with a fresh temp directory that is not inside any git
   repository. Asserts that precondition first, so a temp dir that git
   resolves to an enclosing repo fails loudly instead of passing."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "release-scan-"}))]
    (try
      (is (not (zero? (:exit (shell/sh "git" "rev-parse" "--git-dir" :dir dir))))
          "fixture precondition: temp dir must not be inside a git repo")
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn ^{:stratum 0} scan-local
  [root-path]
  (#'ai.miniforge.phase-software-factory.release/git-dirty-files root-path))

(defn ^{:stratum 0} scan-capsule
  [execute-fn root-path]
  (#'ai.miniforge.phase-software-factory.release/git-dirty-files-capsule
   execute-fn :mock-executor "env-1" root-path))

(defn ^{:stratum 0} stub-execute-fn
  "Executor stub: `responses` maps a command prefix (\"git status\",
   \"cat\") to the result `execute!` returns for it."
  [responses]
  (fn [_executor _env-id command _opts]
    (some (fn [[prefix result]]
            (when (str/starts-with? command prefix) result))
          responses)))

(defn ^{:stratum 0} local-sh-execute-fn
  "Executor stand-in that runs a string command with `sh -c` in the
   workdir, as the OCI and Kubernetes executors do inside a capsule."
  [_executor _env-id command {:keys [workdir]}]
  (let [{:keys [exit out err]} (shell/sh "sh" "-c" command :dir workdir)]
    {:ok? true :data {:exit-code exit :stdout out :stderr err}}))

(def ^{:stratum 0} names-git-quotes
  "File names `git status` without -z prints in C-style quotes."
  ["a b.clj" "\u00e9t\u00e9.clj"])

(defn ^{:stratum 0} with-repo-holding
  "Call `f` with a fresh git repository whose working tree holds an
   untracked `<name>` file (content `content of <name>`) per name."
  [names f]
  (let [dir (str (fs/create-temp-dir {:prefix "release-scan-repo-"}))]
    (try
      (shell/sh "git" "init" "-q" :dir dir)
      (doseq [n names] (spit (io/file dir n) (str "content of " n)))
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn ^{:stratum 0} release-interceptor []
  (phase/get-phase-interceptor {:phase :release}))

(defn ^{:stratum 0} release-ctx
  [worktree impl-status]
  {:execution/id            (random-uuid)
   :execution/input         {:description "Test release" :title "Add feature"}
   :execution/metrics       {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result {:status impl-status}}}
   :worktree-path           worktree
   :phase-config            {:phase :release}})

(def ^{:stratum 0} index-lock-stderr
  "fatal: Unable to create '/workspace/.git/index.lock': File exists.")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} enter-error
  "Run the release interceptor's :enter on `ctx` and return the thrown
   ExceptionInfo, or nil when enter returned normally."
  [ctx]
  (try
    ((:enter (release-interceptor)) ctx)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(deftest ^{:stratum 1} local-scan-reports-non-repo-dir-test
  (testing "git status in a directory that is not a repo returns a git-status-failed anomaly"
    (with-non-repo-dir
      (fn [dir]
        (let [result (scan-local dir)]
          (is (anomaly/anomaly? result) "a failed scan is not an empty file list")
          (is (= :unavailable (:anomaly/type result)))
          (is (= :release/git-status-failed (get-in result [:anomaly/data :type])))
          (is (pos? (get-in result [:anomaly/data :exit] 0)))
          (is (str/includes? (str (:anomaly/message result)) "not a git repository")
              "git's own error reaches the message"))))))

(deftest ^{:stratum 1} local-scan-reports-missing-directory-test
  (testing "a worktree path that does not exist returns an anomaly, not []"
    (let [missing (str (io/file (System/getProperty "java.io.tmpdir")
                                (str "release-scan-missing-" (random-uuid))))
          result  (scan-local missing)]
      (is (anomaly/anomaly? result))
      (is (= :release/git-status-failed (get-in result [:anomaly/data :type])))
      (is (= missing (get-in result [:anomaly/data :worktree-path]))))))

(deftest ^{:stratum 1} capsule-scan-reports-executor-error-test
  (testing "an executor error result ({:ok? false}) returns an anomaly carrying it"
    (let [exec-error {:code :exec-failed :message "container not running"}
          result     (scan-capsule (stub-execute-fn {"git status" {:ok? false :error exec-error}})
                                   "/workspace")]
      (is (anomaly/anomaly? result))
      (is (= :release/git-status-failed (get-in result [:anomaly/data :type])))
      (is (= exec-error (get-in result [:anomaly/data :executor/error])))
      (is (str/includes? (str (:anomaly/message result)) "container not running")))))

(deftest ^{:stratum 1} capsule-scan-reports-non-zero-exit-test
  (testing "git status exiting non-zero in the capsule (held index.lock) returns an anomaly"
    (let [result (scan-capsule (stub-execute-fn {"git status" {:ok? true
                                                               :data {:exit-code 128
                                                                      :stdout ""
                                                                      :stderr index-lock-stderr}}})
                               "/workspace")]
      (is (anomaly/anomaly? result))
      (is (= 128 (get-in result [:anomaly/data :exit])))
      (is (str/includes? (str (:anomaly/message result)) "index.lock")))))

(deftest ^{:stratum 1} capsule-scan-reports-failed-file-read-test
  (testing "a failed cat of a listed file returns an anomaly instead of empty content"
    (let [result (scan-capsule (stub-execute-fn {"git status" {:ok? true
                                                               :data {:exit-code 0
                                                                      :stdout "?? src/feature.clj\u0000"}}
                                                 "cat"        {:ok? true
                                                               :data {:exit-code 1
                                                                      :stdout ""
                                                                      :stderr "cat: src/feature.clj: Permission denied"}}})
                               "/workspace")]
      (is (anomaly/anomaly? result))
      (is (str/includes? (str (:anomaly/message result)) "Permission denied")))))

(deftest ^{:stratum 1} capsule-scan-reports-thrown-executor-test
  (testing "an executor that throws returns an anomaly with the exception message"
    (let [result (scan-capsule (fn [& _] (throw (ex-info "socket closed" {})))
                               "/workspace")]
      (is (anomaly/anomaly? result))
      (is (str/includes? (str (:anomaly/message result)) "socket closed")))))

(deftest ^{:stratum 1} capsule-scan-reads-listed-files-test
  (testing "a successful capsule scan still returns the listed files with content"
    (let [result (scan-capsule (stub-execute-fn {"git status" {:ok? true
                                                               :data {:exit-code 0
                                                                      :stdout "?? src/feature.clj\u0000 D src/old.clj\u0000"}}
                                                 "cat"        {:ok? true
                                                               :data {:exit-code 0
                                                                      :stdout "(ns feature)"}}})
                               "/workspace")]
      (is (= [{:path "src/feature.clj" :content "(ns feature)" :action :create}
              {:path "src/old.clj" :content "" :action :delete}]
             result)))))

(deftest ^{:stratum 1} capsule-scan-reads-paths-shell-would-mangle-test
  (testing "paths with a space, $, ; or non-ASCII are read as named, through a real sh -c"
    (let [dir   (str (fs/create-temp-dir {:prefix "release-scan-names-"}))
          names ["my file.clj" "Foo$Bar.clj" "semi;colon.clj" "\u00e9.clj"]]
      (try
        (shell/sh "git" "init" "-q" :dir dir)
        (doseq [n names] (spit (io/file dir n) (str "content of " n)))
        (let [result (scan-capsule local-sh-execute-fn dir)]
          (is (not (anomaly/anomaly? result)) (str "scan failed: " (:anomaly/message result)))
          (is (= (set (map (fn [n] {:path n :content (str "content of " n) :action :create}) names))
                 (set result))))
        (finally
          (fs/delete-tree dir))))))

(deftest ^{:stratum 1} local-scan-reads-names-git-quotes-test
  (testing "a path with a space or non-ASCII characters is listed with its content"
    (with-repo-holding (conj names-git-quotes "plain.clj")
      (fn [dir]
        (is (= (set (map (fn [n] {:path n :content (str "content of " n) :action :create})
                         (conj names-git-quotes "plain.clj")))
               (set (scan-local dir))))))))

(deftest ^{:stratum 1} local-release-includes-names-git-quotes-test
  (testing "files whose names git quotes reach the release executor"
    (with-repo-holding names-git-quotes
      (fn [dir]
        (let [seen (atom nil)]
          (with-redefs [release-executor/prepare-release
                        (fn [workflow-state _exec-context _opts]
                          (reset! seen workflow-state)
                          {:success? true :artifacts [] :metrics {:files-written 0}})]
            ((:enter (release-interceptor)) (release-ctx dir :success)))
          (is (= (set names-git-quotes)
                 (->> (:workflow/artifacts @seen)
                      (mapcat (comp :code/files :artifact/content))
                      (map :path)
                      set))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} release-raises-git-failure-not-zero-files-test
  (testing "enter-release surfaces the git error instead of :release/zero-files"
    (with-non-repo-dir
      (fn [dir]
        (let [ex (enter-error (release-ctx dir :success))]
          (is (some? ex) "enter must fail when the scan cannot run")
          (is (= :release/git-status-failed (:type (ex-data ex))))
          (is (str/includes? (str (ex-message ex)) "not a git repository"))
          (testing "and the error handler retries it instead of failing terminally"
            (let [result ((:error (release-interceptor))
                          {:phase {:iterations 0 :budget {:iterations 2}}
                           :phase-config {:phase :release}
                           :execution/metrics {:tokens 0 :duration-ms 0}}
                          ex)]
              (is (phase/retrying? (:phase result))))))))))

(deftest ^{:stratum 2} release-does-not-skip-on-git-failure-test
  (testing "an :already-implemented release whose scan fails is not skipped as complete"
    (with-non-repo-dir
      (fn [dir]
        (let [ex (enter-error (release-ctx dir :already-implemented))]
          (is (some? ex) "a failed scan must not read as 'nothing to release'")
          (is (= :release/git-status-failed (:type (ex-data ex)))))))))

(deftest ^{:stratum 2} governed-release-raises-executor-error-test
  (testing "governed enter-release surfaces the capsule executor's error"
    (let [ctx (assoc (release-ctx "/workspace" :success)
                     :execution/mode        :governed
                     :execution/execute-fn  (stub-execute-fn
                                             {"git status" {:ok? false
                                                            :error {:code :exec-failed
                                                                    :message "container not running"}}})
                     :execution/executor     :mock-executor
                     :execution/environment-id "env-1")
          ex  (enter-error ctx)]
      (is (some? ex))
      (is (= :release/git-status-failed (:type (ex-data ex))))
      (is (str/includes? (str (ex-message ex)) "container not running")))))

(deftest ^{:stratum 2} failed-skip-probe-is-not-rescanned-into-zero-files-test
  (testing "an :already-implemented release whose skip probe fails raises the
            probe's error, even when a rescan would come back clean"
    (let [calls      (atom 0)
          execute-fn (fn [_executor _env-id command _opts]
                       (cond
                         (str/starts-with? command "git status")
                         (if (= 1 (swap! calls inc))
                           {:ok? true :data {:exit-code 128 :stdout "" :stderr index-lock-stderr}}
                           {:ok? true :data {:exit-code 0 :stdout ""}})
                         ;; no unpushed commits either
                         (str/starts-with? command "git log")
                         {:ok? true :data {:exit-code 0 :stdout ""}}))
          ctx        (assoc (release-ctx "/workspace" :already-implemented)
                            :execution/mode           :governed
                            :execution/execute-fn     execute-fn
                            :execution/executor       :mock-executor
                            :execution/environment-id "env-1")
          ex         (enter-error ctx)]
      (is (= :release/git-status-failed (:type (ex-data ex)))
          "the probe's own error, not a terminal :release/zero-files")
      (is (str/includes? (str (ex-message ex)) "index.lock"))
      (testing "and the retried attempt, now scanning clean, skips as before"
        (is (= :completed (get-in ((:enter (release-interceptor)) ctx)
                                  [:phase :status])))))))
