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
  "Unit tests for the pr monitor command's worklist-resume paths.

   Tests cover:
   (a) resume path — worklist exists with open PRs → monitor runs
   (b) resume path — all PRs pruned to empty     → prints status, returns normally
   (c) no-worklist path                          → exits 1
   (d) no-remote-url path                        → exits 1"
  (:require
   [clojure.test :refer [deftest is testing]]
   [babashka.process :as process]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.pr :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and helpers

(def ^:private cli-cfg
  {:default-self-author "miniforge[bot]"
   :min-poll-interval-s 5
   :max-poll-interval-s 3600})

(def ^:private open-pr-entry
  {:pr/url              "https://github.com/org/repo/pull/42"
   :pr/number           42
   :pr/repo             "org/repo"
   :pr/added-at         (java.util.Date.)
   :pr/poll-interval    60
   :pr/abandon-after-hours 72})

(def ^:private sample-worklist
  {:worklist/repo-key   "abc123def456"
   :worklist/prs        [open-pr-entry]
   :worklist/updated-at (java.util.Date.)})

(defn- exit-ex
  "Build an ExceptionInfo that tests catch to detect a shared/exit! call."
  [code]
  (ex-info "exit!" {:code code}))

(defn- capturing-exit
  "Return a map with :calls atom and :fn mock for shared/exit!.
   The mock throws so execution stops after the exit call — matching
   the real System/exit semantics that callers depend on."
  []
  (let [calls (atom [])]
    {:calls calls
     :fn    (fn [code]
               (swap! calls conj code)
               (throw (exit-ex code)))}))

(defn- capturing-display
  "Return a map with :msgs atom and a :fn that accumulates printed strings."
  []
  (let [msgs (atom [])]
    {:msgs msgs
     :fn   (fn [msg] (swap! msgs conj msg))}))

(defn- fake-monitor
  "Atom mimicking a monitor state atom with a default poll interval."
  []
  (atom {:config {:poll-interval-ms 60000}}))

(defn- noop-messages-t
  "Minimal messages/t stub that returns a string embedding the key."
  ([k]   (str (name k)))
  ([k m] (str (name k) " " (pr-str m))))

;------------------------------------------------------------------------------ Layer 1
;; (d) No remote URL → exit 1

(deftest no-remote-url-exits-1-test
  (testing "exits 1 and prints error when git remote get-url fails"
    (let [{exit-calls :calls exit-fn :fn} (capturing-exit)
          {errors :msgs error-fn :fn}     (capturing-display)]
      (with-redefs [#'sut/remote-origin-url      (fn [_path] nil)
                    app-config/pr-monitor-config  (constantly cli-cfg)
                    shared/exit!                  exit-fn
                    display/print-error           error-fn
                    messages/t                    noop-messages-t]
        (try
          (sut/pr-monitor-cmd {:repo "/some/repo"})
          (catch clojure.lang.ExceptionInfo e
            (when-not (= "exit!" (ex-message e)) (throw e)))))
      (is (= [1] @exit-calls) "should call exit! with code 1")
      (is (= 1 (count @errors)) "should print exactly one error"))))

;------------------------------------------------------------------------------ Layer 1
;; (c) No work-list on disk → exit 1

(deftest no-worklist-exits-1-test
  (testing "exits 1 and prints error when load-worklist returns failure"
    (let [{exit-calls :calls exit-fn :fn} (capturing-exit)
          {errors :msgs error-fn :fn}     (capturing-display)]
      (with-redefs [#'sut/remote-origin-url        (fn [_path] "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly cli-cfg)
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/home/pr-monitor/abc123def456.edn")
                    pr-lifecycle/load-worklist      (fn [_path]
                                                      (schema/failure :worklist "not found"))
                    shared/exit!                    exit-fn
                    display/print-error             error-fn
                    messages/t                      noop-messages-t]
        (try
          (sut/pr-monitor-cmd {:repo "/some/repo"})
          (catch clojure.lang.ExceptionInfo e
            (when-not (= "exit!" (ex-message e)) (throw e)))))
      (is (= [1] @exit-calls) "should call exit! with code 1")
      (is (= 1 (count @errors)) "should print exactly one error"))))

;------------------------------------------------------------------------------ Layer 2
;; (b) Work-list exists, all PRs pruned to empty → returns normally (exit 0)

(deftest empty-worklist-after-prune-returns-normally-test
  (testing "prints status message and returns without calling exit! when all PRs are closed"
    (let [{exit-calls :calls exit-fn :fn} (capturing-exit)
          {infos :msgs info-fn :fn}       (capturing-display)
          pruned-worklist                  (assoc sample-worklist :worklist/prs [])]
      (with-redefs [#'sut/remote-origin-url        (fn [_path] "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly cli-cfg)
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist      (fn [_path]
                                                      (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs   (fn [_entry] pruned-worklist)
                    shared/exit!                    exit-fn
                    display/print-info              info-fn
                    display/print-error             (fn [_] nil)
                    messages/t                      noop-messages-t]
        (sut/pr-monitor-cmd {:repo "/some/repo"}))
      (is (empty? @exit-calls) "should not call exit! — empty worklist is not an error")
      (is (pos? (count @infos)) "should print at least one status message")
      (is (some #(.contains % "monitor-worklist-empty") @infos)
          "should mention the empty-worklist key"))))

;------------------------------------------------------------------------------ Layer 2
;; (b) Work-list prune error → exit 1

(deftest prune-error-exits-1-test
  (testing "exits 1 when prune-closed-prs returns an anomaly"
    (let [{exit-calls :calls exit-fn :fn} (capturing-exit)
          {errors :msgs error-fn :fn}     (capturing-display)
          gh-fail                          (anomaly/anomaly :fault "gh cli failed" {})]
      (with-redefs [#'sut/remote-origin-url        (fn [_path] "https://github.com/org/repo.git")
                    app-config/pr-monitor-config    (constantly cli-cfg)
                    app-config/home-dir             (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key  (constantly "abc123def456")
                    pr-lifecycle/worklist-path      (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist      (fn [_path]
                                                      (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs   (fn [_entry] gh-fail)
                    shared/exit!                    exit-fn
                    display/print-info              (fn [_] nil)
                    display/print-error             error-fn
                    messages/t                      noop-messages-t]
        (try
          (sut/pr-monitor-cmd {:repo "/some/repo"})
          (catch clojure.lang.ExceptionInfo e
            (when-not (= "exit!" (ex-message e)) (throw e)))))
      (is (= [1] @exit-calls) "should call exit! with code 1 on prune anomaly")
      (is (= 1 (count @errors)) "should print exactly one error"))))

;------------------------------------------------------------------------------ Layer 2
;; (a) Work-list exists, open PRs remain → create monitor and run loop

(deftest resume-from-worklist-runs-monitor-test
  (testing "creates monitor with worklist poll-interval and runs loop when PRs are open"
    (let [monitor-opts-seen (atom nil)
          loop-ran          (atom false)
          mon               (fake-monitor)]
      (with-redefs [#'sut/remote-origin-url          (fn [_path] "https://github.com/org/repo.git")
                    app-config/pr-monitor-config      (constantly cli-cfg)
                    app-config/home-dir               (constantly "/fake/home")
                    pr-lifecycle/worklist-repo-key    (constantly "abc123def456")
                    pr-lifecycle/worklist-path        (constantly "/fake/path.edn")
                    pr-lifecycle/load-worklist        (fn [_path]
                                                        (schema/success :worklist sample-worklist))
                    pr-lifecycle/prune-closed-prs     (fn [entry] entry)
                    pr-lifecycle/create-pr-monitor    (fn [opts]
                                                        (reset! monitor-opts-seen opts)
                                                        mon)
                    pr-lifecycle/run-pr-monitor-loop  (fn [_monitor _author]
                                                        (reset! loop-ran true)
                                                        {:comments-received 0})
                    pr-lifecycle/stop-pr-monitor-loop (fn [_m] nil)
                    process/sh                        (fn [& args]
                                                        (if (= ["gh" "api" "user" "--jq" ".login"]
                                                               (vec args))
                                                          {:exit 0 :out "miniforge[bot]" :err ""}
                                                          {:exit 1 :out "" :err ""}))
                    display/print-info                (fn [_] nil)
                    display/print-error               (fn [_] nil)
                    messages/t                        noop-messages-t]
        (sut/pr-monitor-cmd {:repo "/some/repo"}))
      (is @loop-ran "should have called run-pr-monitor-loop")
      (is (some? @monitor-opts-seen) "should have called create-pr-monitor")
      (is (= "/some/repo" (:worktree-path @monitor-opts-seen))
          "monitor worktree-path should be the --repo value")
      (is (= (* 60 1000) (:poll-interval-ms @monitor-opts-seen))
          "poll-interval-ms should come from the worklist PR entry (60s × 1000)"))))

(comment
  ;; Run just these tests from the REPL
  (clojure.test/run-tests 'ai.miniforge.cli.main.commands.pr-monitor-test)
  :leave-this-here)
