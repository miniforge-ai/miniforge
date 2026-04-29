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

(defn- shell-result
  "Build the shape exec-fn callers expect: {:data {:stdout str}}."
  [stdout]
  {:data {:stdout stdout}})

(defn- recording-exec-fn
  "Return [exec-fn calls-atom replies-atom]. exec-fn records every command into
   calls-atom and looks up its reply in replies-map by exact substring match.
   default is the fallback when nothing matches."
  ([replies-map] (recording-exec-fn replies-map (shell-result "")))
  ([replies-map default]
   (let [calls (atom [])]
     [(fn [cmd]
        (swap! calls conj cmd)
        (or (some (fn [[needle reply]]
                    (when (str/includes? cmd needle) reply))
                  replies-map)
            default))
      calls])))

(def ^:private head-sha "abc123def456")

;------------------------------------------------------------------------------ Layer 1
;; git-persist!

(deftest git-persist!-no-changes-test
  (testing "Empty `git status --porcelain` ⇒ no commit, no push, ok with :no-changes? true"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status" (shell-result "")
                            "rev-parse"  (shell-result head-sha)})
          r (sut/git-persist! exec-fn {:branch "task/foo" :message "checkpoint"})]
      (is (result/ok? r))
      (let [data (result/unwrap r)]
        (is (false? (:persisted? data)))
        (is (true?  (:no-changes? data)))
        (is (nil?   (:commit-sha data))))
      ;; git add was attempted, but commit/push were not.
      (is (some #(str/includes? % "git add -A")        @calls))
      (is (some #(str/includes? % "git status")        @calls))
      (is (not (some #(str/includes? % "git commit") @calls)))
      (is (not (some #(str/includes? % "git push")   @calls))))))

(deftest git-persist!-with-changes-commits-and-pushes-test
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
      ;; Verify expected commands ran in order.
      (let [cmds @calls]
        (is (some #(str/includes? % "git add -A") cmds))
        (is (some #(str/includes? % "git commit -m 'phase: implement'") cmds))
        (is (some #(str/includes? % "git push origin HEAD:feature/bar --force") cmds))))))

(deftest git-persist!-defaults-branch-and-message-test
  (testing "Missing :branch and :message use 'task/unknown' and 'phase checkpoint'"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git status"   (shell-result " M file\n")
                            "git rev-parse" (shell-result head-sha)})]
      (sut/git-persist! exec-fn {})
      (is (some #(str/includes? % "git commit -m 'phase checkpoint'") @calls))
      (is (some #(str/includes? % "HEAD:task/unknown")                @calls)))))

(deftest git-persist!-trims-stdout-test
  (testing "stdout from git rev-parse is trimmed before being returned"
    (let [[exec-fn _] (recording-exec-fn
                       {"git status"   (shell-result " M file\n")
                        "git rev-parse" (shell-result (str "  " head-sha "  \n"))})
          r (sut/git-persist! exec-fn {:branch "task/x"})]
      (is (= head-sha (:commit-sha (result/unwrap r)))))))

(deftest git-persist!-catches-exec-exceptions-test
  (testing "An exception from exec-fn is caught and turned into a :persist-failed result/err"
    (let [boom-exec-fn (fn [cmd]
                         (if (str/includes? cmd "status")
                           (throw (Exception. "git unavailable"))
                           (shell-result "")))
          r (sut/git-persist! boom-exec-fn {:branch "task/x"})]
      (is (result/err? r))
      (is (= :persist-failed (-> r :error :code))))))

;------------------------------------------------------------------------------ Layer 1
;; git-restore!

(deftest git-restore!-fetches-checks-out-and-reads-head-test
  (testing "git-restore! issues fetch + checkout + rev-parse and returns the sha"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git rev-parse" (shell-result (str head-sha "\n"))})
          r (sut/git-restore! exec-fn {:branch "feature/restore"})]
      (is (result/ok? r))
      (let [data (result/unwrap r)]
        (is (true? (:restored? data)))
        (is (= head-sha (:commit-sha data)))
        (is (= "feature/restore" (:branch data))))
      ;; Verify the expected git invocations occurred against the supplied branch.
      (let [cmds @calls]
        (is (some #(str/includes? % "git fetch origin feature/restore") cmds))
        (is (some #(str/includes? % "git checkout feature/restore")     cmds))))))

(deftest git-restore!-defaults-branch-test
  (testing "Missing :branch defaults to 'task/unknown'"
    (let [[exec-fn calls] (recording-exec-fn
                           {"git rev-parse" (shell-result head-sha)})]
      (sut/git-restore! exec-fn {})
      (is (some #(str/includes? % "git fetch origin task/unknown") @calls))
      (is (some #(str/includes? % "git checkout task/unknown")     @calls)))))

(deftest git-restore!-trims-stdout-test
  (testing "git rev-parse stdout is trimmed before return"
    (let [[exec-fn _] (recording-exec-fn
                       {"git rev-parse" (shell-result (str "\n" head-sha "\n"))})
          r (sut/git-restore! exec-fn {:branch "task/x"})]
      (is (= head-sha (:commit-sha (result/unwrap r)))))))

(deftest git-restore!-catches-exec-exceptions-test
  (testing "An exception from exec-fn is caught and turned into a :restore-failed result/err"
    (let [boom-exec-fn (fn [_cmd] (throw (Exception. "fetch refused")))
          r (sut/git-restore! boom-exec-fn {:branch "task/x"})]
      (is (result/err? r))
      (is (= :restore-failed (-> r :error :code))))))
