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
(ns ai.miniforge.release-executor.sandbox-test
  "Unit tests for sandbox operations.

   Uses a mock executor to verify correct command generation
   without requiring Docker."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.sandbox :as sandbox]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Mock executor
;; ============================================================================
(defn ^{:stratum 0} create-mock-executor
  "Create a mock executor that records commands and returns configurable results.

   Options:
   - :responses - map of command-substring -> {:exit-code :stdout :stderr}
   - :default-response - default response for unmatched commands

   Returns [executor commands-atom] where commands-atom captures all executed commands."
  [& {:keys [responses default-response]
      :or {responses {}
           default-response {:exit-code 0 :stdout "" :stderr ""}}}]
  (let [commands (atom [])]
    [(reify
       dag/TaskExecutor
       (executor-type [_] :mock)
       (available? [_] (dag/ok {:available? true}))
       (acquire-environment! [_ _ _] (dag/ok {:environment-id "mock-env"}))
       (execute! [_ _env-id command _opts]
         (swap! commands conj command)
         (let [response (or (some (fn [[substr resp]]
                                    (when (clojure.string/includes? (str command) substr)
                                      resp))
                                  responses)
                            default-response)]
           (dag/ok response)))
       (copy-to! [_ _ _ _] (dag/ok {}))
       (copy-from! [_ _ _ _] (dag/ok {}))
       (release-environment! [_ _] (dag/ok {:released? true}))
       (environment-status [_ _] (dag/ok {:status :running})))
     commands]))

(deftest ^{:stratum 0} write-file-roundtrip-base64-test
  (testing "write-file! base64 encoding is valid"
    (let [content "(ns foo)\n(defn bar [x]\n  (* x 2))"
          encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (.getBytes content "UTF-8"))
          decoded (String. (.decode (java.util.Base64/getDecoder) encoded) "UTF-8")]
      (is (= content decoded)))))

(deftest ^{:stratum 0} push-branch-ssh-fail-https-fallback-test
  (testing "push-branch! retries with HTTPS when SSH push fails"
    (let [push-count (atom 0)
          cmds (atom [])
          tracking-exec (reify
                            dag/TaskExecutor
                            (executor-type [_] :mock)
                            (available? [_] (dag/ok {:available? true}))
                            (acquire-environment! [_ _ _] (dag/ok {}))
                            (execute! [_ _env-id command _opts]
                              (swap! cmds conj command)
                              (if (clojure.string/includes? (str command) "git push")
                                (let [n (swap! push-count inc)]
                                  (if (= n 1)
                                    (dag/ok {:exit-code 1 :stdout "" :stderr "signing failed"})
                                    (dag/ok {:exit-code 0 :stdout "" :stderr ""})))
                                (dag/ok (cond
                                          (clojure.string/includes? (str command) "get-url")
                                          {:exit-code 0 :stdout "git@github.com:org/repo.git" :stderr ""}
                                          :else {:exit-code 0 :stdout "" :stderr ""}))))
                            (copy-to! [_ _ _ _] (dag/ok {}))
                            (copy-from! [_ _ _ _] (dag/ok {}))
                            (release-environment! [_ _] (dag/ok {}))
                            (environment-status [_ _] (dag/ok {:status :running})))]
        (sandbox/push-branch! tracking-exec "env-1" "feat/test" {:env {"GH_TOKEN" "tok123"}})
        (is (= 2 @push-count))
        ;; Should have set-url to HTTPS, pushed, then restored original URL
        (is (some #(clojure.string/includes? (str %) "x-access-token") @cmds))
        (is (some #(clojure.string/includes? (str %) "git@github.com") @cmds)))))

(deftest ^{:stratum 0} push-branch-https-setup-failure-test
  (testing "push-branch! fails without retrying the push when repointing origin to the token URL fails"
    (let [push-count (atom 0)
          tracking-exec (reify
                            dag/TaskExecutor
                            (executor-type [_] :mock)
                            (available? [_] (dag/ok {:available? true}))
                            (acquire-environment! [_ _ _] (dag/ok {}))
                            (execute! [_ _env-id command _opts]
                              (cond
                                (clojure.string/includes? (str command) "git push")
                                (do (swap! push-count inc)
                                    (dag/ok {:exit-code 1 :stdout "" :stderr "signing failed"}))

                                (clojure.string/includes? (str command) "get-url")
                                (dag/ok {:exit-code 0 :stdout "git@github.com:org/repo.git" :stderr ""})

                                (clojure.string/includes? (str command) "set-url")
                                (dag/ok {:exit-code 1 :stdout "" :stderr "config locked"})

                                :else (dag/ok {:exit-code 0 :stdout "" :stderr ""})))
                            (copy-to! [_ _ _ _] (dag/ok {}))
                            (copy-from! [_ _ _ _] (dag/ok {}))
                            (release-environment! [_ _] (dag/ok {}))
                            (environment-status [_ _] (dag/ok {:status :running})))
          result (sandbox/push-branch! tracking-exec "env-1" "feat/test" {:env {"GH_TOKEN" "tok123"}})]
      (is (not (:success? result)))
      (is (false? (:push-succeeded? result)))
      (is (= 1 @push-count) "push is not retried when the origin repoint itself failed")
      (is (clojure.string/includes? (:error result) "token fallback could not be applied")))))

(deftest ^{:stratum 0} push-branch-https-restore-failure-test
  (testing "push-branch! fails loud when the https-fallback push succeeds but restoring
            the original origin URL fails, since a token-bearing URL may be left persisted"
    (let [push-count (atom 0)
          set-url-count (atom 0)
          tracking-exec (reify
                            dag/TaskExecutor
                            (executor-type [_] :mock)
                            (available? [_] (dag/ok {:available? true}))
                            (acquire-environment! [_ _ _] (dag/ok {}))
                            (execute! [_ _env-id command _opts]
                              (cond
                                (clojure.string/includes? (str command) "git push")
                                (let [n (swap! push-count inc)]
                                  (if (= n 1)
                                    (dag/ok {:exit-code 1 :stdout "" :stderr "signing failed"})
                                    (dag/ok {:exit-code 0 :stdout "" :stderr ""})))

                                (clojure.string/includes? (str command) "get-url")
                                (dag/ok {:exit-code 0 :stdout "git@github.com:org/repo.git" :stderr ""})

                                (clojure.string/includes? (str command) "set-url")
                                (let [n (swap! set-url-count inc)]
                                  (if (= n 1)
                                    (dag/ok {:exit-code 0 :stdout "" :stderr ""})
                                    (dag/ok {:exit-code 1 :stdout "" :stderr "config locked"})))

                                :else (dag/ok {:exit-code 0 :stdout "" :stderr ""})))
                            (copy-to! [_ _ _ _] (dag/ok {}))
                            (copy-from! [_ _ _ _] (dag/ok {}))
                            (release-environment! [_ _] (dag/ok {}))
                            (environment-status [_ _] (dag/ok {:status :running})))
          result (sandbox/push-branch! tracking-exec "env-1" "feat/test" {:env {"GH_TOKEN" "tok123"}})]
      (is (not (:success? result)) "restore failure fails loud even though the push itself succeeded")
      (is (true? (:push-succeeded? result)))
      (is (clojure.string/includes? (:error result) "Scrub it")))))

(deftest ^{:stratum 0} exec-executor-error-includes-output-test
  (testing "exec! includes :output even when the executor itself errors (not just a nonzero exit)"
    (let [failing-exec (reify
                          dag/TaskExecutor
                          (executor-type [_] :mock)
                          (available? [_] (dag/ok {:available? true}))
                          (acquire-environment! [_ _ _] (dag/ok {}))
                          (execute! [_ _env-id _command _opts]
                            (dag/err :executor-unavailable "container is gone"))
                          (copy-to! [_ _ _ _] (dag/ok {}))
                          (copy-from! [_ _ _ _] (dag/ok {}))
                          (release-environment! [_ _] (dag/ok {}))
                          (environment-status [_ _] (dag/ok {:status :running})))
          result (sandbox/exec! failing-exec "env-1" "git status")]
      (is (not (:success? result)))
      (is (contains? result :output)
          "executor-level failures must still carry an :output key, matching the docstring's contract")
      (is (= "" (:output result))))))

(deftest ^{:stratum 0} push-with-https-fallback-threads-opts-test
  (testing "push-with-https-fallback! passes the caller's opts (e.g. :workdir) to the
            set-url and restore calls, not just the push itself"
    (let [push-count (atom 0)
          seen-opts (atom [])
          tracking-exec (reify
                            dag/TaskExecutor
                            (executor-type [_] :mock)
                            (available? [_] (dag/ok {:available? true}))
                            (acquire-environment! [_ _ _] (dag/ok {}))
                            (execute! [_ _env-id command opts]
                              (cond
                                (clojure.string/includes? (str command) "git push")
                                (let [n (swap! push-count inc)]
                                  (if (= n 1)
                                    (dag/ok {:exit-code 1 :stdout "" :stderr "signing failed"})
                                    (dag/ok {:exit-code 0 :stdout "" :stderr ""})))

                                (clojure.string/includes? (str command) "get-url")
                                (dag/ok {:exit-code 0 :stdout "git@github.com:org/repo.git" :stderr ""})

                                (clojure.string/includes? (str command) "set-url")
                                (do (swap! seen-opts conj opts)
                                    (dag/ok {:exit-code 0 :stdout "" :stderr ""}))

                                :else (dag/ok {:exit-code 0 :stdout "" :stderr ""})))
                            (copy-to! [_ _ _ _] (dag/ok {}))
                            (copy-from! [_ _ _ _] (dag/ok {}))
                            (release-environment! [_ _] (dag/ok {}))
                            (environment-status [_ _] (dag/ok {:status :running})))]
      (sandbox/push-branch! tracking-exec "env-1" "feat/test"
                            {:env {"GH_TOKEN" "tok123"} :workdir "/repo"})
      (is (= 2 (count @seen-opts)) "repoint + restore both hit the tracked branch")
      (is (every? #(= "/repo" (:workdir %)) @seen-opts)
          "both set-url calls carry the caller's :workdir, matching the push"))))

;; ============================================================================
;; safe container path validation tests
;; ============================================================================
(defn- ^{:stratum 0} assert-rejected-path
  [result expected-type expected-message-pattern]
  (is (false? (:success? result)))
  (is (re-find expected-message-pattern (:error result)))
  (is (= expected-type (:type result))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; check-gh-auth! tests
;; ============================================================================
(deftest ^{:stratum 1} check-gh-auth-success-test
  (testing "check-gh-auth! returns authenticated when gh auth status succeeds"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth status" {:exit-code 0 :stdout "Logged in" :stderr ""}})
          result (sandbox/check-gh-auth! exec "env-1")]
      (is (:available? result))
      (is (:authenticated? result)))))

(deftest ^{:stratum 1} check-gh-auth-failure-test
  (testing "check-gh-auth! returns unauthenticated when gh auth status fails"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth status" {:exit-code 1 :stdout "" :stderr "not logged in"}})
          result (sandbox/check-gh-auth! exec "env-1")]
      (is (:available? result))
      (is (not (:authenticated? result))))))

(deftest ^{:stratum 1} check-gh-auth-with-token-opts-test
  (testing "check-gh-auth! accepts opts for GH_TOKEN injection"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth status" {:exit-code 0 :stdout "Logged in" :stderr ""}})
          result (sandbox/check-gh-auth! exec "env-1" {:env {"GH_TOKEN" "test-token"}})]
      (is (:available? result))
      (is (:authenticated? result)))))

(deftest ^{:stratum 1} check-gh-auth-two-arity-backward-compatible-test
  (testing "check-gh-auth! works with 2-arg call (no opts)"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth status" {:exit-code 0 :stdout "Logged in" :stderr ""}})
          result (sandbox/check-gh-auth! exec "env-1")]
      (is (:authenticated? result)))))

;; ============================================================================
;; create-branch! tests
;; ============================================================================
(deftest ^{:stratum 1} create-branch-success-test
  (testing "create-branch! issues fetch and checkout commands"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch" {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout" {:exit-code 0 :stdout "" :stderr ""}})
          result (sandbox/create-branch! exec "env-1" "feat/my-branch")]
      (is (:success? result))
      (is (= "feat/my-branch" (:branch result)))
      (is (= "main" (:base-branch result)))
      ;; Verify commands were issued
      (is (some #(clojure.string/includes? % "git fetch origin main") @cmds))
      (is (some #(clojure.string/includes? % "git checkout -b feat/my-branch") @cmds)))))

(deftest ^{:stratum 1} create-branch-fetch-failure-test
  (testing "create-branch! fails when fetch fails"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                    "git fetch" {:exit-code 1 :stdout "" :stderr "fatal: fetch failed"}})
          result (sandbox/create-branch! exec "env-1" "feat/branch")]
      (is (not (:success? result)))
      (is (clojure.string/includes? (:error result) "fetch")))))

(deftest ^{:stratum 1} create-branch-uses-head-not-origin-base-test
  (testing "create-branch! checks out off HEAD so phase-boundary commits
            already on the task branch carry forward into the release branch"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch" {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout" {:exit-code 0 :stdout "" :stderr ""}})
          result (sandbox/create-branch! exec "env-1" "release/x")]
      (is (:success? result))
      (is (= "main" (:base-branch result)))
      (let [checkout (some #(when (clojure.string/includes? % "git checkout -b release/x") %) @cmds)]
        (is (some? checkout)
            "checkout command must be issued for the new branch")
        (is (not (clojure.string/includes? checkout "origin/main"))
            "checkout must not reset to origin/<base>; HEAD is the source of truth
             so prior phase-boundary commits stay on the new branch")))))

(deftest ^{:stratum 1} commits-ahead-of-base-parses-count-test
  (testing "commits-ahead-of-base returns the parsed integer count"
    (let [[exec _] (create-mock-executor
                    :responses {"git rev-list" {:exit-code 0 :stdout "3\n" :stderr ""}})]
      (is (= 3 (sandbox/commits-ahead-of-base exec "env-1" "main")))))
  (testing "commits-ahead-of-base returns nil on git failure"
    (let [[exec _] (create-mock-executor
                    :responses {"git rev-list" {:exit-code 128 :stdout "" :stderr "fatal"}})]
      (is (nil? (sandbox/commits-ahead-of-base exec "env-1" "main")))))
  (testing "commits-ahead-of-base returns nil on unparseable output (treat as unknown, not zero)"
    (let [[exec _] (create-mock-executor
                    :responses {"git rev-list" {:exit-code 0 :stdout "" :stderr ""}})]
      (is (nil? (sandbox/commits-ahead-of-base exec "env-1" "main"))))))

;; ============================================================================
;; write-file! tests
;; ============================================================================
(deftest ^{:stratum 1} write-file-generates-base64-command-test
  (testing "write-file! encodes content as base64 and creates parent dirs"
    (let [[exec cmds] (create-mock-executor)
          result (sandbox/write-file! exec "env-1" "src/foo.clj" "(ns foo)")]
      (is (:success? result))
      (let [cmd (first @cmds)]
        ;; Should contain mkdir -p for parent dir
        (is (clojure.string/includes? cmd "mkdir -p"))
        ;; Should contain base64 decode
        (is (clojure.string/includes? cmd "base64 -d"))))))

;; ============================================================================
;; delete-file! tests
;; ============================================================================
(deftest ^{:stratum 1} delete-file-command-test
  (testing "delete-file! issues rm -f command"
    (let [[exec cmds] (create-mock-executor)]
      (sandbox/delete-file! exec "env-1" "src/old.clj")
      (is (= 1 (count @cmds)))
      (is (clojure.string/includes? (first @cmds) "rm -f")))))

;; ============================================================================
;; stage-files! tests
;; ============================================================================
(deftest ^{:stratum 1} stage-all-files-test
  (testing "stage-files! with :all issues git add ."
    (let [[exec cmds] (create-mock-executor)]
      (sandbox/stage-files! exec "env-1" :all)
      (is (= "git add ." (first @cmds))))))

(deftest ^{:stratum 1} stage-specific-files-test
  (testing "stage-files! with specific paths issues git add with paths"
    (let [[exec cmds] (create-mock-executor)]
      (sandbox/stage-files! exec "env-1" ["src/a.clj" "src/b.clj"])
      (let [cmd (first @cmds)]
        (is (clojure.string/includes? cmd "git add"))
        (is (clojure.string/includes? cmd "src/a.clj"))
        (is (clojure.string/includes? cmd "src/b.clj"))))))

;; ============================================================================
;; commit-changes! tests
;; ============================================================================
(deftest ^{:stratum 1} commit-changes-success-test
  (testing "commit-changes! commits and returns sha"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git commit" {:exit-code 0 :stdout "1 file changed" :stderr ""}
                                   "git rev-parse" {:exit-code 0 :stdout "abc1234\n" :stderr ""}})
          result (sandbox/commit-changes! exec "env-1" "feat: add feature")]
      (is (:success? result))
      (is (= "abc1234" (:commit-sha result)))
      (is (some #(clojure.string/includes? % "git commit") @cmds)))))

(deftest ^{:stratum 1} commit-changes-escapes-quotes-test
  (testing "commit-changes! escapes single quotes in commit message"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git commit" {:exit-code 0 :stdout "" :stderr ""}
                                   "git rev-parse" {:exit-code 0 :stdout "def5678\n" :stderr ""}})]
      (sandbox/commit-changes! exec "env-1" "fix: it's working")
      (let [cmd (first @cmds)]
        ;; Should contain escaped single quote
        (is (clojure.string/includes? cmd "it'\\''s working"))))))

(deftest ^{:stratum 1} commit-changes-rev-parse-failure-test
  (testing "commit-changes! surfaces a rev-parse failure instead of reporting a
            phantom success with a missing sha"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"git commit" {:exit-code 0 :stdout "1 file changed" :stderr ""}
                                    "git rev-parse" {:exit-code 1 :stdout "" :stderr "fatal: bad revision 'HEAD'"}})
          result (sandbox/commit-changes! exec "env-1" "feat: add feature")]
      (is (not (:success? result)))
      (is (nil? (:commit-sha result)))
      (is (clojure.string/includes? (:error result) "bad revision")))))

;; ============================================================================
;; push-branch! tests
;; ============================================================================
(deftest ^{:stratum 1} push-branch-command-test
  (testing "push-branch! issues git push -u origin"
    (let [[exec cmds] (create-mock-executor)
          result (sandbox/push-branch! exec "env-1" "feat/branch")]
      (is (:success? result))
      (is (clojure.string/includes? (first @cmds) "git push -u origin feat/branch")))))

;; ============================================================================
;; create-pr! tests
;; ============================================================================
(deftest ^{:stratum 1} create-pr-success-test
  (testing "create-pr! calls gh pr create and parses PR URL"
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh pr create" {:exit-code 0
                                                   :stdout "https://github.com/org/repo/pull/42\n"
                                                   :stderr ""}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "Add feature"
                                      :body "Description here"
                                      :base-branch "main"})]
      (is (:success? result))
      (is (= 42 (:pr-number result)))
      (is (= "https://github.com/org/repo/pull/42" (:pr-url result)))
      (let [cmd (first @cmds)]
        (is (clojure.string/includes? cmd "gh pr create"))
        (is (clojure.string/includes? cmd "--title"))
        (is (clojure.string/includes? cmd "--base main"))))))

(deftest ^{:stratum 1} create-pr-failure-test
  (testing "create-pr! returns failure when gh pr create fails"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh pr create" {:exit-code 1
                                                    :stdout ""
                                                    :stderr "not authenticated"}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "PR" :body "" :base-branch "main"})]
      (is (not (:success? result)))
      (is (some? (:error result))))))

(deftest ^{:stratum 1} create-pr-unconfirmed-is-failure-test
  (testing "gh pr create exits 0 but prints no PR URL → FAILURE, not a phantom
            success (the original 'doc but no PR' symptom)"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh pr create" {:exit-code 0
                                                    :stdout ""
                                                    :stderr ""}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "PR" :body "" :base-branch "main"})]
      (is (not (:success? result)) "unconfirmed PR must not report success")
      (is (nil? (:pr-number result)))
      (is (some? (:error result))))))

(deftest ^{:stratum 1} create-pr-reuses-existing-pr-test
  (testing "a branch that already has an open PR reuses it (no duplicate)"
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh pr create"
                                   {:exit-code 1 :stdout ""
                                    :stderr "a pull request for branch \"feat/x\" already exists"}
                                   "gh pr view"
                                   {:exit-code 0
                                    :stdout "https://github.com/org/repo/pull/7\n" :stderr ""}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "PR" :body "" :base-branch "main"})]
      (is (:success? result) "existing PR is reused as success")
      (is (= 7 (:pr-number result)))
      (is (= "https://github.com/org/repo/pull/7" (:pr-url result)))
      (is (some #(clojure.string/includes? % "gh pr view") @cmds)
          "resolves the existing PR via gh pr view"))))

(deftest ^{:stratum 1} create-pr-already-exists-but-unresolvable-fails-test
  (testing "PR already exists but gh pr view can't resolve it → failure (no phantom)"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh pr create"
                                    {:exit-code 1 :stdout ""
                                     :stderr "a pull request already exists"}
                                    "gh pr view"
                                    {:exit-code 1 :stdout "" :stderr "no pull requests found"}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "PR" :body "" :base-branch "main"})]
      (is (not (:success? result)))
      (is (nil? (:pr-number result)))
      (is (clojure.string/includes? (:error result) "no pull requests found")
          "error surfaces the gh pr view resolution failure, not the create error"))))

;; ============================================================================
;; write-and-stage-files! tests
;; ============================================================================
(deftest ^{:stratum 1} write-and-stage-files-success-test
  (testing "write-and-stage-files! processes all code artifacts"
    (let [[exec cmds] (create-mock-executor)
          code-artifacts [{:code/files [{:action :create :path "src/a.clj" :content "(ns a)"}
                                        {:action :modify :path "src/b.clj" :content "(ns b)"}
                                        {:action :delete :path "src/old.clj"}]}]
          result (sandbox/write-and-stage-files! exec "env-1" code-artifacts)]
      (is (:success? result))
      (is (= 1 (get-in result [:metrics :files-written])))
      (is (= 1 (get-in result [:metrics :files-modified])))
      (is (= 1 (get-in result [:metrics :files-deleted])))
      (is (= 3 (get-in result [:metrics :total-operations])))
      ;; Should have: write, write, delete, stage = 4 commands
      (is (= 4 (count @cmds)))
      ;; Last command should be path-specific git add
      (is (= "git add 'src/a.clj' 'src/b.clj' 'src/old.clj'" (last @cmds))))))

(deftest ^{:stratum 1} write-and-stage-files-failure-test
  (testing "write-and-stage-files! reports errors from failed operations"
    (let [[exec _cmds] (create-mock-executor
                        :default-response {:exit-code 1 :stdout "" :stderr "permission denied"})
          code-artifacts [{:code/files [{:action :create :path "src/a.clj" :content "(ns a)"}]}]
          result (sandbox/write-and-stage-files! exec "env-1" code-artifacts)]
      (is (not (:success? result)))
      (is (seq (:errors result))))))

;; ============================================================================
;; push-branch! HTTPS fallback tests
;; ============================================================================
(deftest ^{:stratum 1} push-branch-success-test
  (testing "push-branch! succeeds on first try without fallback"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git push" {:exit-code 0 :stdout "" :stderr ""}})]
      (sandbox/push-branch! exec "env-1" "feat/test" {:env {"GH_TOKEN" "tok123"}})
      (is (= 1 (count @cmds)))
      (is (clojure.string/includes? (first @cmds) "git push")))))

(deftest ^{:stratum 1} push-branch-no-token-no-fallback-test
  (testing "push-branch! returns failure without fallback when no GH_TOKEN"
    (let [[exec _cmds] (create-mock-executor
                        :default-response {:exit-code 1 :stdout "" :stderr "signing failed"})
          result (sandbox/push-branch! exec "env-1" "feat/test")]
      (is (= {:success? false
              :error "signing failed"
              :output ""}
             result)))))

(deftest ^{:stratum 1} write-file-rejects-path-traversal-test
  (testing "write-file! returns failure on path containing .. segment"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/write-file! exec "env-1" "../etc/passwd" "evil")
       :path-traversal
       #"Path traversal rejected"))))

(deftest ^{:stratum 1} write-file-rejects-embedded-traversal-test
  (testing "write-file! returns failure on path with embedded .. segment"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/write-file! exec "env-1" "src/../../etc/passwd" "evil")
       :path-traversal
       #"Path traversal rejected"))))

(deftest ^{:stratum 1} write-file-rejects-single-quote-injection-test
  (testing "write-file! returns failure on path containing single-quote (shell injection)"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/write-file! exec "env-1" "src/a'.clj" "evil")
       :shell-injection
       #"Shell injection rejected"))))

(deftest ^{:stratum 1} write-file-rejects-dollar-injection-test
  (testing "write-file! returns failure on path containing $ (shell injection)"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/write-file! exec "env-1" "src/$HOME/x.clj" "evil")
       :shell-injection
       #"Shell injection rejected"))))

(deftest ^{:stratum 1} write-file-rejects-semicolon-injection-test
  (testing "write-file! returns failure on path containing ; (shell injection)"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/write-file! exec "env-1" "src/a;rm -rf /;b.clj" "evil")
       :shell-injection
       #"Shell injection rejected"))))

(deftest ^{:stratum 1} delete-file-rejects-path-traversal-test
  (testing "delete-file! returns failure on path containing .. segment"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/delete-file! exec "env-1" "../etc/shadow")
       :path-traversal
       #"Path traversal rejected"))))

(deftest ^{:stratum 1} delete-file-rejects-single-quote-injection-test
  (testing "delete-file! returns failure on path containing single-quote (shell injection)"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/delete-file! exec "env-1" "src/a' || rm -rf /;b.clj")
       :shell-injection
       #"Shell injection rejected"))))

(deftest ^{:stratum 1} write-file-accepts-normal-paths-test
  (testing "write-file! accepts well-formed relative source paths"
    (let [[exec _cmds] (create-mock-executor)]
      (is (:success? (sandbox/write-file! exec "env-1" "src/foo/bar.clj" "(ns foo.bar)"))))))

(deftest ^{:stratum 1} write-file-accepts-deep-paths-test
  (testing "write-file! accepts deep nested paths without traversal"
    (let [[exec _cmds] (create-mock-executor)]
      (is (:success? (sandbox/write-file! exec "env-1"
                                          "components/my-comp/src/ai/company/my_comp/core.clj"
                                          "(ns ai.company.my-comp.core)"))))))

(deftest ^{:stratum 1} write-file-rejects-absolute-path-test
  (testing "write-file! returns failure on absolute path (cannot escape container workspace)"
    (let [[exec _cmds] (create-mock-executor)]
      (assert-rejected-path
       (sandbox/write-file! exec "env-1" "/etc/passwd" "evil")
       :path-traversal
       #"Path traversal rejected"))))
