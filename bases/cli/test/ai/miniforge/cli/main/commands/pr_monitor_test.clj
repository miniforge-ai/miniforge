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

(ns ai.miniforge.cli.main.commands.pr-monitor-test
  "Unit tests for pr-monitor-cmd's worklist-resume and fresh-monitor paths.

   Coverage:
     (a) resume path — worklist open PRs → monitor created and loop runs
     (b) resume path — all PRs pruned to empty → returns normally, no exit call
     (c) no-worklist path                  → shared/exit! 1
     (d) no-remote-url path                → shared/exit! 1
     (e) prune anomaly path                → shared/exit! 1
     (f) nil-author path                   → shared/exit! 1
     (g) fresh --author path               → run-monitor!, no work-list load"
  (:require
   [clojure.test :refer [deftest is testing]]
   [babashka.process :as process]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.pr-monitor :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private cli-cfg
  {:default-self-author "miniforge[bot]"
   :min-poll-interval-s 5
   :max-poll-interval-s 3600})

(def ^:private open-pr-entry
  {:pr/url                 "https://github.com/org/repo/pull/42"
   :pr/number              42
   :pr/repo                "org/repo"
   :pr/added-at            (java.util.Date.)
   :pr/poll-interval       60
   :pr/abandon-after-hours 72})

(def ^:private sample-worklist
  {:worklist/repo-key   "abc123def456"
   :worklist/prs        [open-pr-entry]
   :worklist/updated-at (java.util.Date.)})

(defn- exit-ex [code] (ex-info "exit!" {:code code}))

(defn- capturing-msgs
  "display/print-* stub that records every string."
  []
  (let [msgs (atom [])]
    {:msgs msgs :fn (fn [msg] (swap! msgs conj msg))}))

(defn- fake-monitor
  "Atom that mimics a monitor state atom with a populated poll-interval."
  []
  (atom {:config {:poll-interval-ms 60000}}))

(defn- noop-t
  "messages/t stub: returns a string embedding the key name so tests can
   check which key was used without loading the full message catalog."
  ([k]   (name k))
  ([k _] (name k)))

(defn- run-cmd
  "Call sut/pr-monitor-cmd, catching exit! exceptions. Returns the exit code
   when exit! was called, or nil for a normal return."
  [opts]
  (try
    (sut/pr-monitor-cmd opts)
    nil
    (catch clojure.lang.ExceptionInfo e
      (when (= "exit!" (ex-message e))
        (:code (ex-data e))))))

;------------------------------------------------------------------------------ Layer 1
;; (d) No remote URL → exit 1

(deftest no-remote-url-exits-1-test
  (testing "exits 1 when git remote get-url origin fails"
    (let [{errors :msgs err-fn :fn} (capturing-msgs)]
      (with-redefs [sut/remote-origin-url     (constantly nil)
                    app-config/pr-monitor-config (constantly cli-cfg)
                    shared/exit!                 (fn [code] (throw (exit-ex code)))
                    display/print-error          err-fn
                    messages/t                   noop-t]
        (is (= 1 (run-cmd {:repo "/some/repo"}))))
      (is (= 1 (count @errors))))))

;------------------------------------------------------------------------------ Layer 1
;; (c) No work-list on disk → exit 1

(deftest no-worklist-exits-1-test
  (testing "exits 1 when load-worklist returns a failure result"
    (let [{errors :msgs err-fn :fn} (capturing-msgs)]
      (with-redefs [sut/remote-origin-url        (constantly "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly cli-cfg)
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist      (fn [_] (schema/failure :worklist "not found"))
                    shared/exit!                    (fn [code] (throw (exit-ex code)))
                    display/print-error             err-fn
                    messages/t                      noop-t]
        (is (= 1 (run-cmd {:repo "/some/repo"}))))
      (is (= 1 (count @errors))))))

;------------------------------------------------------------------------------ Layer 1
;; (b) All PRs pruned → returns normally (exit 0 by default)

(deftest empty-worklist-after-prune-returns-normally-test
  (testing "prints status and returns without calling exit! when all PRs are closed"
    (let [{infos :msgs info-fn :fn}  (capturing-msgs)
          pruned-wl                   (assoc sample-worklist :worklist/prs [])]
      (with-redefs [sut/remote-origin-url        (constantly "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly cli-cfg)
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist      (fn [_] (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs   (fn [_] pruned-wl)
                    shared/exit!                    (fn [code] (throw (exit-ex code)))
                    display/print-info              info-fn
                    display/print-error             (fn [_] nil)
                    messages/t                      noop-t]
        (is (nil? (run-cmd {:repo "/some/repo"})) "should return normally, not exit"))
      (is (some #(.contains % "monitor-worklist-empty") @infos)
          "should print the empty-worklist key"))))

;------------------------------------------------------------------------------ Layer 1
;; (e) Prune returns anomaly → exit 1

(deftest prune-anomaly-exits-1-test
  (testing "exits 1 when prune-closed-prs returns an anomaly"
    (let [{errors :msgs err-fn :fn} (capturing-msgs)
          gh-fail                    (anomaly/anomaly :fault "gh cli failed" {})]
      (with-redefs [sut/remote-origin-url        (constantly "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly cli-cfg)
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist      (fn [_] (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs   (fn [_] gh-fail)
                    shared/exit!                    (fn [code] (throw (exit-ex code)))
                    display/print-info              (fn [_] nil)
                    display/print-error             err-fn
                    messages/t                      noop-t]
        (is (= 1 (run-cmd {:repo "/some/repo"}))))
      (is (= 1 (count @errors))))))

;------------------------------------------------------------------------------ Layer 1
;; (f) Author unresolvable → exit 1

(deftest nil-author-exits-1-test
  (testing "exits 1 when neither gh api user nor default-self-author yields an author"
    (let [{errors :msgs err-fn :fn} (capturing-msgs)]
      (with-redefs [sut/remote-origin-url        (constantly "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly (dissoc cli-cfg :default-self-author))
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist      (fn [_] (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs   (fn [entry] entry)
                    ;; gh api user fails → resolve-author falls through to nil default
                    process/sh                      (fn [& _] {:exit 1 :out "" :err ""})
                    shared/exit!                    (fn [code] (throw (exit-ex code)))
                    display/print-info              (fn [_] nil)
                    display/print-error             err-fn
                    messages/t                      noop-t]
        (is (= 1 (run-cmd {:repo "/some/repo"}))))
      (is (= 1 (count @errors))))))

;------------------------------------------------------------------------------ Layer 2
;; (a) Open PRs in worklist → monitor created and loop runs

(deftest resume-from-worklist-runs-monitor-test
  (testing "creates monitor with worklist-derived poll-interval and runs loop"
    (let [monitor-opts-seen (atom nil)
          loop-ran          (atom false)
          mon               (fake-monitor)]
      (with-redefs [sut/remote-origin-url          (constantly "https://github.com/org/repo.git")
                    app-config/pr-monitor-config      (constantly cli-cfg)
                    app-config/home-dir               (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key    (constantly "abc123def456")
                    pr-lifecycle/worklist-path        (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist        (fn [_] (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs     (fn [entry] entry)
                    pr-lifecycle/create-pr-monitor    (fn [opts]
                                                        (reset! monitor-opts-seen opts)
                                                        mon)
                    pr-lifecycle/run-pr-monitor-loop  (fn [_monitor _author]
                                                        (reset! loop-ran true)
                                                        {:comments-received 0})
                    pr-lifecycle/stop-pr-monitor-loop (fn [_] nil)
                    ;; gh api user → return the configured default
                    process/sh                        (fn [& args]
                                                        (if (= ["gh" "api" "user" "--jq" ".login"]
                                                               (vec args))
                                                          {:exit 0 :out "miniforge[bot]" :err ""}
                                                          {:exit 1 :out "" :err ""}))
                    display/print-info                (fn [_] nil)
                    display/print-error               (fn [_] nil)
                    messages/t                        noop-t]
        (run-cmd {:repo "/some/repo"}))
      (is @loop-ran "should have called run-pr-monitor-loop")
      (is (some? @monitor-opts-seen) "should have called create-pr-monitor")
      (is (= "/some/repo" (:worktree-path @monitor-opts-seen)))
      (is (= (* 60 1000) (:poll-interval-ms @monitor-opts-seen))
          "poll-interval-ms should derive from the PR entry's :pr/poll-interval (60 s)"))))

(deftest fresh-author-path-skips-worklist-test
  (testing "(g) --author starts a fresh monitor and never loads a work-list (Copilot #1209)"
    (let [loaded?           (atom false)
          monitor-opts-seen (atom nil)
          loop-author       (atom nil)
          mon               (fake-monitor)]
      (with-redefs [pr-lifecycle/load-worklist        (fn [_]
                                                        (reset! loaded? true)
                                                        (schema/failure :worklist "should not be called"))
                    pr-lifecycle/create-pr-monitor    (fn [opts] (reset! monitor-opts-seen opts) mon)
                    pr-lifecycle/run-pr-monitor-loop  (fn [_monitor author]
                                                        (reset! loop-author author)
                                                        {:comments-received 0})
                    pr-lifecycle/stop-pr-monitor-loop (fn [_] nil)
                    app-config/pr-monitor-config      (constantly cli-cfg)
                    display/print-info                (fn [_] nil)
                    display/print-error               (fn [_] nil)
                    messages/t                        noop-t]
        (run-cmd {:author "alice" :repo "/some/repo"}))
      (is (false? @loaded?) "fresh --author path must NOT load a work-list")
      (is (= "alice" @loop-author) "monitors the supplied author")
      (is (= "/some/repo" (:worktree-path @monitor-opts-seen)))
      (is (= "alice" (:self-author @monitor-opts-seen))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.cli.main.commands.pr-monitor-test)
  :leave-this-here)
