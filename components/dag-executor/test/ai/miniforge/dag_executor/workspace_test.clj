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

;------------------------------------------------------------------------------ Layer 2

;; git-persist!
(deftest ^{:stratum 2} git-persist!-no-changes-test
  (testing "Empty `git status --porcelain` ⇒ no commit, no push, ok with :no-changes? true"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status" (shell-result "")
                            "rev-parse"  (shell-result head-sha)})
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
                           {"git status"   (shell-result " M src/foo.clj\n")
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
        (is (some #(and (vector? %) (cmd-has? % "git push origin HEAD:feature/bar --force")) cmds)
            "push must be an arg vector, not a shell string")))))

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
