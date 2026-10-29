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
(ns ai.miniforge.dag-executor.workspace-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.workspace :as sut]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

;; Test fixtures and factories
(defn- ^{:stratum 0} shell-result
  "Build the shape exec-fn callers expect: {:data {:stdout str}}."
  [stdout]
  {:data {:stdout stdout}})

(defn- ^{:stratum 0} cmd->str
  "Normalize a command — string or arg vector — to a single string for matching."
  [cmd]
  (if (string? cmd) cmd (str/join " " cmd)))

(def ^{:stratum 0} ^:private head-sha "abc123def456")

(deftest ^{:stratum 0} git-restore!-catches-exec-exceptions-test
  (testing "An exception from exec-fn is caught and turned into a :restore-failed result/err"
    (let [boom-exec-fn (fn [_cmd] (throw (Exception. "fetch refused")))
          r (sut/git-restore! boom-exec-fn {:branch "task/x"})]
      (is (result/err? r))
      (is (= :restore-failed (-> r :error :code))))))

(deftest ^{:stratum 0} git-persist!-commit-failure-is-an-error-test
  (testing "a non-zero commit exit becomes :persist-commit-failed, never :persisted? true"
    (let [exec-fn (fn [cmd]
                    (cond
                      (and (vector? cmd) (some #{"commit"} cmd))
                      (result/ok {:exit-code 128 :stdout "" :stderr "error: 1Password: failed to fill whole buffer"})
                      (and (string? cmd) (str/includes? cmd "status"))
                      (result/ok {:exit-code 0 :stdout " M a.clj\n" :stderr ""})
                      :else (result/ok {:exit-code 0 :stdout "" :stderr ""})))
          r (sut/git-persist! exec-fn {:branch "task/x" :message "m"})]
      (is (result/err? r))
      (is (= :persist-commit-failed (get-in r [:error :code])))
      (is (str/includes? (get-in r [:error :message]) "1Password")))))

(deftest ^{:stratum 0} git-persist!-push-failure-is-an-error-test
  (let [exec-fn (fn [cmd]
                  (cond
                    (and (vector? cmd) (some #{"push"} cmd))
                    (result/ok {:exit-code 1 :stdout "" :stderr "rejected"})
                    (and (string? cmd) (str/includes? cmd "status"))
                    (result/ok {:exit-code 0 :stdout " M a.clj\n" :stderr ""})
                    :else (result/ok {:exit-code 0 :stdout "" :stderr ""})))
        r (sut/git-persist! exec-fn {:branch "task/x" :message "m"})]
    (is (result/err? r))
    (is (= :persist-push-failed (get-in r [:error :code])))))

(deftest ^{:stratum 0} git-restore!-fetch-and-checkout-failures-are-errors-test
  (let [failing (fn [needle code]
                  (fn [cmd]
                    (if (and (vector? cmd) (some #{needle} cmd))
                      (result/ok {:exit-code 1 :stdout "" :stderr (str needle " failed")})
                      (result/ok {:exit-code 0 :stdout "abc\n" :stderr ""}))))]
    (is (= :restore-fetch-failed (get-in (sut/git-restore! (failing "fetch" 1) {:branch "task/x"}) [:error :code])))
    (is (= :restore-checkout-failed (get-in (sut/git-restore! (failing "checkout" 1) {:branch "task/x"}) [:error :code])))))

(deftest ^{:stratum 0} rev-parse-failure-is-an-error-test
  (let [exec-fn (fn [cmd]
                  (cond
                    (and (string? cmd) (str/includes? cmd "rev-parse")) (result/ok {:exit-code 128 :stdout "" :stderr "not a git repo"})
                    (and (string? cmd) (str/includes? cmd "status")) (result/ok {:exit-code 0 :stdout " M a\n" :stderr ""})
                    :else (result/ok {:exit-code 0 :stdout "" :stderr ""})))]
    (is (= :persist-sha-failed (get-in (sut/git-persist! exec-fn {:branch "task/x"}) [:error :code])))
    (is (= :restore-sha-failed (get-in (sut/git-restore! exec-fn {:branch "task/x"}) [:error :code])))))

(deftest ^{:stratum 0} exec-fn-result-err-is-surfaced-test
  (testing "an exec-fn that returns result/err on commit is a persist-commit-failed, not success"
    (let [exec-fn (fn [cmd]
                    (cond
                      (and (vector? cmd) (some #{"commit"} cmd)) (result/err :exec-failed "container gone")
                      (and (string? cmd) (str/includes? cmd "status")) (result/ok {:exit-code 0 :stdout " M a\n" :stderr ""})
                      :else (result/ok {:exit-code 0 :stdout "" :stderr ""})))
          r (sut/git-persist! exec-fn {:branch "task/x"})]
      (is (= :persist-commit-failed (get-in r [:error :code])))
      (is (= "container gone" (get-in r [:error :message])))))
  (testing "a failing status step is not read as no changes"
    (let [exec-fn (fn [cmd] (if (and (string? cmd) (str/includes? cmd "status"))
                              (result/err :exec-failed "status blew up")
                              (result/ok {:exit-code 0 :stdout "" :stderr ""})))
          r (sut/git-persist! exec-fn {:branch "task/x"})]
      (is (= :persist-status-failed (get-in r [:error :code]))))))

(defn- ^{:stratum 0} single-branch-clone
  "A bare remote with one commit on main, cloned with --single-branch as
   the governed bootstrap does. Returns the clone's directory."
  []
  (let [tmp    (str (java.nio.file.Files/createTempDirectory
                     "persist-single-branch" (make-array java.nio.file.attribute.FileAttribute 0)))
        remote (str tmp "/remote.git")
        seed   (str tmp "/seed")
        clone  (str tmp "/clone")
        git    (fn [dir & args] (apply shell/sh "git" "-C" dir args))]
    (shell/sh "git" "init" "-q" "--bare" "-b" "main" remote)
    (shell/sh "git" "init" "-q" "-b" "main" seed)
    (doseq [dir [seed]]
      (git dir "config" "user.email" "t@t") (git dir "config" "user.name" "t"))
    (spit (str seed "/a.txt") "a\n")
    (git seed "add" "-A")
    (git seed "-c" "commit.gpgsign=false" "commit" "-qm" "base")
    (git seed "push" "-q" remote "main")
    (shell/sh "git" "clone" "-q" "--branch" "main" "--single-branch" remote clone)
    (git clone "config" "user.email" "t@t")
    (git clone "config" "user.name" "t")
    clone))

(defn- ^{:stratum 0} single-branch-clone-of
  "A second --single-branch clone of CLONE's origin, at main."
  [clone]
  (let [remote (str/trim (:out (shell/sh "git" "-C" clone "remote" "get-url" "origin")))
        dir    (str (java.nio.file.Files/createTempDirectory
                     "persist-second-clone" (make-array java.nio.file.attribute.FileAttribute 0)) "/c")]
    (shell/sh "git" "clone" "-q" "--branch" "main" "--single-branch" remote dir)
    (shell/sh "git" "-C" dir "config" "user.email" "t@t")
    (shell/sh "git" "-C" dir "config" "user.name" "t")
    dir))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} cmd-has?
  "True if cmd (raw string or vector) contains substring s when normalized."
  [cmd s]
  (str/includes? (cmd->str cmd) s))

(defn- ^{:stratum 1} recording-exec-fn
  "Return [exec-fn calls-atom]. exec-fn records every command in its **raw**
   form (string or arg vector) into calls-atom, preserving the distinction so
   tests can assert that user-supplied git commands arrive as vectors (not
   shell strings). Reply lookup uses the normalized form. default is the
   fallback when nothing matches."
  ([replies-map] (recording-exec-fn replies-map (shell-result "")))
  ([replies-map default]
   (let [calls (atom [])]
     [(fn [cmd]
        (let [cmd-str (cmd->str cmd)]
          (swap! calls conj cmd)
          (or (some (fn [[needle reply]]
                      (when (str/includes? cmd-str needle) reply))
                    replies-map)
              default)))
      calls])))

(deftest ^{:stratum 1} git-persist!-catches-exec-exceptions-test
  (testing "An exception from exec-fn is caught and turned into a :persist-failed result/err"
    (let [boom-exec-fn (fn [cmd]
                         (if (str/includes? cmd "status")
                           (throw (Exception. "git unavailable"))
                           (shell-result "")))
          r (sut/git-persist! boom-exec-fn {:branch "task/x"})]
      (is (result/err? r))
      (is (= :persist-failed (-> r :error :code))))))

(deftest ^{:stratum 1} git-persist!-rejects-leading-dash-branch-test
  (testing "A branch starting with '-' is rejected before exec-fn is called"
    (let [exec-called (atom false)
          exec-fn (fn [_] (reset! exec-called true) (shell-result ""))
          r (sut/git-persist! exec-fn {:branch "--evil"})]
      (is (result/err? r))
      (is (= :invalid-branch (-> r :error :code)))
      (is (false? @exec-called) "exec-fn must not be invoked for an invalid branch"))))

(deftest ^{:stratum 1} git-persist!-rejects-empty-branch-test
  (testing "An empty string branch is rejected before exec-fn is called"
    (let [exec-called (atom false)
          exec-fn (fn [_] (reset! exec-called true) (shell-result ""))
          r (sut/git-persist! exec-fn {:branch ""})]
      (is (result/err? r))
      (is (= :invalid-branch (-> r :error :code)))
      (is (false? @exec-called) "exec-fn must not be invoked for an empty branch"))))

(deftest ^{:stratum 1} git-restore!-rejects-leading-dash-branch-test
  (testing "A branch starting with '-' is rejected before exec-fn is called"
    (let [exec-called (atom false)
          exec-fn (fn [_] (reset! exec-called true) (shell-result ""))
          r (sut/git-restore! exec-fn {:branch "--evil"})]
      (is (result/err? r))
      (is (= :invalid-branch (-> r :error :code)))
      (is (false? @exec-called) "exec-fn must not be invoked for an invalid branch"))))

(deftest ^{:stratum 1} git-restore!-rejects-empty-branch-test
  (testing "An empty string branch is rejected before exec-fn is called"
    (let [exec-called (atom false)
          exec-fn (fn [_] (reset! exec-called true) (shell-result ""))
          r (sut/git-restore! exec-fn {:branch ""})]
      (is (result/err? r))
      (is (= :invalid-branch (-> r :error :code)))
      (is (false? @exec-called) "exec-fn must not be invoked for an empty branch"))))

(deftest ^{:stratum 1} git-persist!-creates-a-destination-branch-head-already-reaches-test
  (testing "HEAD on another remote branch (here the base) is not the destination: the branch is created"
    (let [clone   (single-branch-clone)
          exec-fn (fn [cmd]
                    (let [r (if (string? cmd)
                              (shell/sh "sh" "-c" cmd :dir clone)
                              (apply shell/sh (concat cmd [:dir clone])))]
                      {:data {:exit-code (:exit r) :stdout (:out r) :stderr (:err r)}}))
          r       (sut/git-persist! exec-fn {:branch "task/new"})]
      (is (true? (:pushed? (result/unwrap r))) "a clean worktree still publishes a missing branch")
      (is (str/includes? (:out (shell/sh "git" "-C" clone "ls-remote" "origin" "refs/heads/task/new"))
                         (:commit-sha (result/unwrap r)))))))

(deftest ^{:stratum 1} git-persist!-a-clean-persist-never-rewinds-the-remote-test
  (testing "the destination moved ahead of a clean checkout: the push is rejected, not forced"
    (let [ahead   (single-branch-clone)
          behind  (single-branch-clone-of ahead)
          run-in  (fn [dir]
                    (fn [cmd]
                      (let [r (if (string? cmd)
                                (shell/sh "sh" "-c" cmd :dir dir)
                                (apply shell/sh (concat cmd [:dir dir])))]
                        {:data {:exit-code (:exit r) :stdout (:out r) :stderr (:err r)}})))]
      (spit (str ahead "/a.txt") "a\nahead\n")
      (let [pushed (:commit-sha (result/unwrap (sut/git-persist! (run-in ahead) {:branch "task/x"})))
            r      (sut/git-persist! (run-in behind) {:branch "task/x"})]
        (is (= :persist-push-failed (get-in r [:error :code])))
        (is (str/starts-with? (:out (shell/sh "git" "-C" ahead "ls-remote" "origin" "refs/heads/task/x"))
                              pushed)
            "the remote branch still points at the newer commit")))))

(deftest ^{:stratum 1} git-persist!-after-a-push-a-clean-persist-is-a-no-op-test
  (testing "--single-branch: the pushed task branch is not re-pushed by the next clean persist"
    (let [clone   (single-branch-clone)
          exec-fn (fn [cmd]
                    (let [r (if (string? cmd)
                              (shell/sh "sh" "-c" cmd :dir clone)
                              (apply shell/sh (concat cmd [:dir clone])))]
                      {:data {:exit-code (:exit r) :stdout (:out r) :stderr (:err r)}}))]
      (spit (str clone "/a.txt") "a\nb\n")
      (is (true? (:pushed? (result/unwrap (sut/git-persist! exec-fn {:branch "task/x"})))))
      (is (true? (:no-changes? (result/unwrap (sut/git-persist! exec-fn {:branch "task/x"}))))))))

;------------------------------------------------------------------------------ Layer 2

;; git-persist!
(deftest ^{:stratum 2} git-persist!-no-changes-test
  (testing "Empty `git status --porcelain` and the branch already at HEAD ⇒ no commit, no push, :no-changes? true"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status"    (shell-result "")
                            "git ls-remote" (shell-result (str head-sha "\trefs/heads/task/foo\n"))
                            "rev-parse"     (shell-result head-sha)})
          r (sut/git-persist! exec-fn {:branch "task/foo" :message "checkpoint"})]
      (is (result/ok? r))
      (let [data (result/unwrap r)]
        (is (false? (:persisted? data)))
        (is (true?  (:no-changes? data)))
        (is (nil?   (:commit-sha data)))
        (is (= "task/foo" (:branch data))))
      ;; git add was attempted, but commit/push were not.
      (is (some #(cmd-has? % "git add -A") @calls))
      (is (some #(cmd-has? % "git status") @calls))
      (is (not (some #(cmd-has? % " commit -m") @calls)))
      (is (not (some #(cmd-has? % "git push")   @calls))))))

(deftest ^{:stratum 2} git-persist!-with-changes-commits-and-pushes-test
  (testing "Dirty `git status` ⇒ commit + push + read HEAD sha; ok carries the sha and branch"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status"    (shell-result " M src/foo.clj\n")
                            "git ls-remote" (shell-result "abc999\trefs/heads/feature/bar\n")
                            "git rev-parse" (shell-result (str head-sha "\n"))})
          r (sut/git-persist! exec-fn {:branch "feature/bar"
                                       :message "phase: implement"})]
      (is (result/ok? r))
      (let [data (result/unwrap r)]
        (is (true? (:persisted? data)))
        (is (= head-sha (:commit-sha data)))
        (is (= "feature/bar" (:branch data))))
      ;; Verify expected commands ran and that user-supplied values travel via
      ;; arg vectors (not shell strings) — the security invariant of this PR.
      (let [cmds @calls]
        (is (some #(cmd-has? % "git add -A") cmds))
        (is (some #(and (vector? %) (cmd-has? % "-c commit.gpgsign=false commit -m phase: implement")) cmds)
            "commit must be an arg vector, not a shell string")
        (is (some #(and (vector? %)
                        (cmd-has? % "git push --force-with-lease=feature/bar:abc999 origin HEAD:feature/bar"))
                  cmds)
            "push is an arg vector, forced only over the remote SHA just read")))))

(deftest ^{:stratum 2} git-persist!-defaults-branch-and-message-test
  (testing "Missing :branch and :message use 'task/unknown' and 'phase checkpoint'"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status"   (shell-result " M file\n")
                            "git rev-parse" (shell-result head-sha)})]
      (sut/git-persist! exec-fn {})
      (is (some #(cmd-has? % "-c commit.gpgsign=false commit -m phase checkpoint") @calls))
      (is (some #(cmd-has? % "HEAD:task/unknown")               @calls)))))

(deftest ^{:stratum 2} git-persist!-trims-stdout-test
  (testing "stdout from git rev-parse is trimmed before being returned"
    (let [[exec-fn _] (recording-exec-fn
                       {"git status"   (shell-result " M file\n")
                        "git rev-parse" (shell-result (str "  " head-sha "  \n"))})
          r (sut/git-persist! exec-fn {:branch "task/x"})]
      (is (= head-sha (:commit-sha (result/unwrap r)))))))

;; git-restore!
(deftest ^{:stratum 2} git-restore!-fetches-checks-out-and-reads-head-test
  (testing "git-restore! issues fetch + checkout + rev-parse and returns the sha"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git rev-parse" (shell-result (str head-sha "\n"))})
          r (sut/git-restore! exec-fn {:branch "feature/restore"})]
      (is (result/ok? r))
      (let [data (result/unwrap r)]
        (is (true? (:restored? data)))
        (is (= head-sha (:commit-sha data)))
        (is (= "feature/restore" (:branch data))))
      ;; Verify the expected git invocations occurred and that branch is passed
      ;; via arg vector — the security invariant this PR establishes.
      (let [cmds @calls]
        (is (some #(and (vector? %) (cmd-has? % "git fetch origin feature/restore")) cmds)
            "fetch must be an arg vector, not a shell string")
        (is (some #(and (vector? %) (cmd-has? % "git checkout feature/restore")) cmds)
            "checkout must be an arg vector, not a shell string")))))

(deftest ^{:stratum 2} remote-url-replaces-origin-test
  (testing "a :remote URL is the ls-remote and push target, and the fetch
            from it writes origin's tracking ref so the checkout still finds
            the branch"
    (let [url "https://github.com/o/r.git"
          [persist-fn persist-calls] (recording-exec-fn
                                      {"git status" (shell-result " M f")
                                       "git rev-parse" (shell-result head-sha)})
          [restore-fn restore-calls] (recording-exec-fn
                                      {"git rev-parse" (shell-result head-sha)})]
      (sut/git-persist! persist-fn {:branch "task/x" :remote url})
      (sut/git-restore! restore-fn {:branch "task/x" :remote url})
      (is (some #{["git" "ls-remote" url "refs/heads/task/x"]} @persist-calls))
      (is (some #{["git" "push" "--force-with-lease=task/x:" url "HEAD:task/x"]} @persist-calls))
      (is (some #{["git" "fetch" url "+refs/heads/task/x:refs/remotes/origin/task/x"]}
                @restore-calls)))))

(deftest ^{:stratum 2} git-restore!-defaults-branch-test
  (testing "Missing :branch defaults to 'task/unknown'"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git rev-parse" (shell-result head-sha)})]
      (sut/git-restore! exec-fn {})
      (is (some #(cmd-has? % "git fetch origin task/unknown") @calls))
      (is (some #(cmd-has? % "git checkout task/unknown")     @calls)))))

(deftest ^{:stratum 2} git-restore!-trims-stdout-test
  (testing "git rev-parse stdout is trimmed before return"
    (let [[exec-fn _] (recording-exec-fn
                       {"git rev-parse" (shell-result (str "\n" head-sha "\n"))})
          r (sut/git-restore! exec-fn {:branch "task/x"})]
      (is (= head-sha (:commit-sha (result/unwrap r)))))))

(deftest ^{:stratum 2} git-persist!-push?-false-commits-without-pushing-test
  (testing ":push? false commits locally, never pushes, and says so"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status"    (shell-result " M src/a.clj")
                            "git rev-parse" (shell-result head-sha)})
          r (sut/git-persist! exec-fn {:branch "task/x" :message "m" :push? false})]
      (is (result/ok? r))
      (is (= {:persisted? true :pushed? false :commit-sha head-sha :branch "task/x"}
             (result/unwrap r)))
      (is (some #(cmd-has? % "commit") @calls))
      (is (not-any? #(cmd-has? % "push") @calls))))
  (testing "the default still pushes"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status"    (shell-result " M src/a.clj")
                            "git rev-parse" (shell-result head-sha)})]
      (is (true? (:pushed? (result/unwrap (sut/git-persist! exec-fn {:branch "task/x"})))))
      (is (some #(cmd-has? % "git push") @calls)))))

(deftest ^{:stratum 2} git-persist!-pushes-a-held-commit-from-a-clean-worktree-test
  (testing "a held commit followed by a clean, allowed persist is pushed"
    (let [[held-exec held-calls] (recording-exec-fn {"git status"    (shell-result " M src/a.clj")
                                                     "git rev-parse" (shell-result head-sha)})
          [clean-exec calls]     (recording-exec-fn {"git status"    (shell-result "")
                                                     "git ls-remote" (shell-result "abc\trefs/heads/task/x\n")
                                                     "git rev-parse" (shell-result head-sha)})
          held  (sut/git-persist! held-exec {:branch "task/x" :push? false})
          later (sut/git-persist! clean-exec {:branch "task/x"})]
      (is (false? (:pushed? (result/unwrap held))))
      (is (not-any? #(cmd-has? % "push") @held-calls))
      (is (= {:persisted? true :pushed? true :commit-sha head-sha :branch "task/x"}
             (result/unwrap later)))
      (is (some #(cmd-has? % "git push") @calls))
      (is (not-any? #(cmd-has? % "commit") @calls) "nothing new to commit")))
  (testing "a clean worktree with nothing pending is still a no-op"
    (let [[exec-fn calls] (recording-exec-fn {"git status"    (shell-result "")
                                              "git ls-remote" (shell-result (str head-sha "\trefs/heads/task/x\n"))
                                              "git rev-parse" (shell-result head-sha)})]
      (is (true? (:no-changes? (result/unwrap (sut/git-persist! exec-fn {:branch "task/x"})))))
      (is (not-any? #(cmd-has? % "push") @calls)))))

(deftest ^{:stratum 2} git-persist!-pending-check-failure-is-an-error-test
  (testing "a failed ls-remote on a clean worktree is an error, not :no-changes?"
    (let [[exec-fn calls] (recording-exec-fn {"git status"    (shell-result "")
                                              "git ls-remote" {:data {:exit-code 128 :stderr "bad"}}})
          r (sut/git-persist! exec-fn {:branch "task/x"})]
      (is (= :persist-pending-failed (get-in r [:error :code])))
      (is (not-any? #(cmd-has? % "push") @calls)))))
