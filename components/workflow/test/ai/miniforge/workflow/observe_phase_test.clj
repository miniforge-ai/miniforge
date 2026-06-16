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

(ns ai.miniforge.workflow.observe-phase-test
  (:require
   [ai.miniforge.clock.interface :as clock]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workflow.observe-phase :as sut]
   [clojure.test :refer [deftest is testing]]))

(def ^:private sample-pr
  ;; Mirrors the production :workflow/pr-info shape built in
  ;; phase-software-factory/release.clj (:pr-number/:pr-url/:branch/:commit-sha).
  {:pr-number 42 :pr-url "https://github.com/o/r/pull/42" :branch "mf/x" :commit-sha "deadbeef"})

;;------------------------------------------------------------------------------
;; pr-url->repo

(deftest pr-url->repo-test
  (testing "standard GitHub PR URL extracts owner/repo"
    (is (= "org/repo" (#'sut/pr-url->repo "https://github.com/org/repo/pull/42"))))
  (testing "URL with numeric PR number still parses"
    (is (= "myorg/myrepo" (#'sut/pr-url->repo "https://github.com/myorg/myrepo/pull/1"))))
  (testing "non-GitHub URL without 'pull' segment returns nil"
    (is (nil? (#'sut/pr-url->repo "https://gitlab.com/org/repo/merge_requests/1"))))
  (testing "nil returns nil"
    (is (nil? (#'sut/pr-url->repo nil))))
  (testing "non-string value returns nil"
    (is (nil? (#'sut/pr-url->repo 42))))
  (testing "bare repo URL with no pull path returns nil"
    (is (nil? (#'sut/pr-url->repo "https://github.com/org/repo"))))
  (testing "malformed string with no slashes returns nil"
    (is (nil? (#'sut/pr-url->repo "not-a-url"))))
  (testing "empty string returns nil"
    (is (nil? (#'sut/pr-url->repo "")))))

;;------------------------------------------------------------------------------
;; pr-info->worklist-entry

(deftest pr-info->worklist-entry-test
  (let [fixed-now (java.util.Date. 0)]
    (testing ":pr/url + :pr/number key shape (DAG release shape)"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/42" :pr/number 42}
                   60000 72 fixed-now)]
        (is (= "https://github.com/org/repo/pull/42" (:pr/url entry)))
        (is (= 42 (:pr/number entry)))
        (is (= "org/repo" (:pr/repo entry)))
        (is (= fixed-now (:pr/added-at entry)))
        (is (= 60 (:pr/poll-interval entry)))
        (is (= 72 (:pr/abandon-after-hours entry)))))

    (testing ":pr-url + :pr-number key shape (single-PR release shape)"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr-url "https://github.com/org/repo/pull/7" :pr-number 7}
                   30000 48 fixed-now)]
        (is (= "https://github.com/org/repo/pull/7" (:pr/url entry)))
        (is (= 7 (:pr/number entry)))
        (is (= "org/repo" (:pr/repo entry)))))

    (testing "missing url returns nil"
      (is (nil? (#'sut/pr-info->worklist-entry {:pr/number 1} 60000 72 fixed-now))))

    (testing "missing number returns nil"
      (is (nil? (#'sut/pr-info->worklist-entry
                 {:pr/url "https://github.com/org/repo/pull/1"}
                 60000 72 fixed-now))))

    (testing "non-GitHub URL with no parseable repo and no explicit :pr/repo returns nil"
      (is (nil? (#'sut/pr-info->worklist-entry
                 {:pr/url "https://notgithub.example.com/pr/1" :pr/number 1}
                 60000 72 fixed-now))))

    (testing "explicit :pr/repo overrides URL parsing"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://notgithub.example.com/pr/1"
                    :pr/number 1
                    :pr/repo "myorg/myrepo"}
                   60000 72 fixed-now)]
        (is (= "myorg/myrepo" (:pr/repo entry)))))

    (testing "nil poll-interval-ms omits :pr/poll-interval key"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}
                   nil 72 fixed-now)]
        (is (some? entry))
        (is (not (contains? entry :pr/poll-interval)))))

    (testing "nil abandon-after-hours omits :pr/abandon-after-hours key"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}
                   60000 nil fixed-now)]
        (is (some? entry))
        (is (not (contains? entry :pr/abandon-after-hours)))))

    (testing "timestamp comes from the now argument, not wall clock"
      (let [t (java.util.Date. 123456789)
            entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}
                   60000 72 t)]
        (is (= t (:pr/added-at entry)))))))

;;------------------------------------------------------------------------------
;; remote-origin-url

(deftest remote-origin-url-test
  (testing "blank worktree-path returns nil without shelling out"
    (is (nil? (#'sut/remote-origin-url ""))))
  (testing "nil worktree-path returns nil without shelling out"
    (is (nil? (#'sut/remote-origin-url nil))))
  (testing "whitespace-only worktree-path returns nil without shelling out"
    (is (nil? (#'sut/remote-origin-url "   ")))))

;;------------------------------------------------------------------------------
;; try-persist-worklist! — never-throws contract

(deftest try-persist-worklist!-exception-swallowed-test
  ;; Access the private remote-origin-url var via ns-resolve to avoid the
  ;; compile-time private check while still allowing with-redefs-fn to swap it.
  (let [remote-url-var (ns-resolve 'ai.miniforge.workflow.observe-phase
                                   'remote-origin-url)]
    (testing "exception from persist-worklist! is swallowed, not rethrown"
      (with-redefs-fn
        {remote-url-var                    (fn [_] "https://github.com/org/repo.git")
         #'pr-lifecycle/worklist-repo-key  (fn [_] "org/repo")
         #'pr-lifecycle/worklist-path      (fn [_ _] "/tmp/wl.edn")
         #'pr-lifecycle/persist-worklist!  (fn [_ _] (throw (ex-info "disk full" {})))
         #'config/miniforge-home           (constantly "/tmp")
         #'clock/now-ms                    (constantly 0)}
        (fn []
          ;; Must return nil, not rethrow
          (is (nil? (#'sut/try-persist-worklist!
                     {:worktree-path "/tmp/repo"
                      :poll-interval-ms 60000
                      :abandon-after-hours 72}
                     "miniforge[bot]"
                     [{:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}]
                     nil))
              "exception from persist-worklist! must not propagate"))))

    (testing "RuntimeException from worklist-repo-key is also swallowed"
      (with-redefs-fn
        {remote-url-var                   (fn [_] "https://github.com/org/repo.git")
         #'pr-lifecycle/worklist-repo-key (fn [_] (throw (RuntimeException. "no key")))
         #'clock/now-ms                   (constantly 0)}
        (fn []
          (is (nil? (#'sut/try-persist-worklist!
                     {:worktree-path "/tmp/repo"
                      :poll-interval-ms 60000
                      :abandon-after-hours 72}
                     "bot" [] nil))))))

    (testing "blank worktree-path skips the persist block without throwing"
      ;; remote-origin-url returns nil for blank path → when block is never entered
      (with-redefs [clock/now-ms (constantly 0)]
        (is (nil? (#'sut/try-persist-worklist!
                   {:worktree-path ""
                    :poll-interval-ms 60000
                    :abandon-after-hours 72}
                   "bot" [] nil)))))

    (testing "persist-worklist! returning a success result produces nil with no warning"
      (with-redefs-fn
        {remote-url-var                    (fn [_] "https://github.com/org/repo.git")
         #'pr-lifecycle/worklist-repo-key  (fn [_] "org/repo")
         #'pr-lifecycle/worklist-path      (fn [_ _] "/tmp/wl.edn")
         #'pr-lifecycle/persist-worklist!  (fn [_ _] (schema/success {}))
         #'config/miniforge-home           (constantly "/tmp")
         #'clock/now-ms                    (constantly 0)}
        (fn []
          (is (nil? (#'sut/try-persist-worklist!
                     {:worktree-path "/tmp/repo"
                      :poll-interval-ms 60000
                      :abandon-after-hours 72}
                     "bot"
                     [{:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}]
                     nil))))))))

;;------------------------------------------------------------------------------
;; resolve-pr-infos

(deftest resolve-pr-infos-test
  (testing "DAG path: returns the populated dag-pr-infos"
    (is (= [sample-pr]
           (vec (#'sut/resolve-pr-infos {:execution/dag-pr-infos [sample-pr]})))))
  (testing "single-PR: reads release result output :workflow/pr-info (the real release path)"
    (is (= [sample-pr]
           (#'sut/resolve-pr-infos
            {:execution/phase-results
             {:release {:result {:output {:workflow/pr-info sample-pr}}}}}))))
  (testing "the deprecated [:metrics :release :pr-info] fallback is NO LONGER read
            (one canonical location — the release output)"
    (is (nil? (#'sut/resolve-pr-infos {:metrics {:release {:pr-info sample-pr}}}))))
  (testing "REGRESSION (the #979 handoff bug): the old shallow [:release :pr-info] key is NOT read"
    (is (nil? (#'sut/resolve-pr-infos
               {:execution/phase-results {:release {:pr-info sample-pr}}}))))
  (testing "no PR anywhere -> nil (phase skips)"
    (is (nil? (#'sut/resolve-pr-infos {}))))
  (testing "empty dag-pr-infos falls through to single-PR resolution"
    (is (= [sample-pr]
           (#'sut/resolve-pr-infos
            {:execution/dag-pr-infos []
             :execution/phase-results
             {:release {:result {:output {:workflow/pr-info sample-pr}}}}})))))

;;------------------------------------------------------------------------------
;; default-config

(deftest default-config-loaded-from-edn-test
  (testing "observe phase defaults come from resource config"
    (is (= :default (:agent sut/default-config)))
    (is (= 259200 (get-in sut/default-config [:budget :time-seconds])))
    (is (= [] (:gates sut/default-config)))))

;;------------------------------------------------------------------------------
;; enter-observe — detached monitor

(deftest enter-observe-detaches-monitor-test
  ;; The 2026-06-15 rn-03 dogfood reached :observe and then "hung" for 45+ min:
  ;; the phase ran the PR-monitor loop synchronously, and that loop polls for up
  ;; to :abandon-after-hours (72h). Observe must NOT block the workflow thread —
  ;; it starts the monitor on a detached future and returns :monitoring at once.
  (testing "enter-observe returns :monitoring immediately without waiting on the monitor loop"
    (let [loop-released (promise)        ; lets the monitor loop finish on demand
          loop-entered (promise)         ; signals the loop actually ran
          monitor-sentinel (Object.)]
      (with-redefs [sut/resolve-monitor-config (fn [& _] {:self-author "miniforge[bot]"})
                    pr-lifecycle/create-pr-monitor (fn [_] monitor-sentinel)
                    pr-lifecycle/run-pr-monitor-loop
                    (fn [monitor author]
                      (deliver loop-entered {:monitor monitor :author author})
                      @loop-released      ; block until the test releases it
                      {:comments-received 0})]
        (let [ctx (sut/enter-observe {:execution/dag-pr-infos [sample-pr]})
              result (get-in ctx [:phase :result])]
          ;; Phase returned while the monitor loop is still blocked — proof it
          ;; did not run synchronously.
          (is (= :completed (get-in ctx [:phase :status])))
          (is (= :monitoring (get-in result [:output :observe/status])))
          (is (true? (get-in result [:output :observe/monitor-detached?])))
          (is (= 1 (get-in result [:output :observe/prs-monitored])))
          (is (future? (:execution/pr-monitor-future ctx)))
          (is (not (realized? loop-released))
              "the monitor loop must still be blocked — observe did not await it")
          ;; The detached loop did receive the monitor + author and is running.
          (is (= {:monitor monitor-sentinel :author "miniforge[bot]"}
                 (deref loop-entered 2000 :timed-out)))
          ;; Release it and confirm the future carries the loop's result.
          (deliver loop-released true)
          (is (= {:comments-received 0}
                 (deref (:execution/pr-monitor-future ctx) 2000 :timed-out))))))))

;;------------------------------------------------------------------------------
;; enter-observe — skip paths

(deftest enter-observe-skips-when-no-prs-test
  (testing "no PRs to observe -> :skipped, no monitor future"
    (let [ctx (sut/enter-observe {})]
      (is (= :completed (get-in ctx [:phase :status])))
      (is (= :skipped (get-in ctx [:phase :result :output :observe/status])))
      (is (nil? (:execution/pr-monitor-future ctx))))))

;;------------------------------------------------------------------------------
;; resolve-monitor-config

(deftest resolve-monitor-config-test
  (testing "context overrides are merged over shared monitor defaults"
    (with-redefs [sut/load-monitor-defaults
                  (fn []
                    {:poll-interval-ms 60000
                     :self-author nil
                     :max-fix-attempts-per-comment 3
                     :max-total-fix-attempts-per-pr 10
                     :abandon-after-hours 72})]
      (let [cfg (#'sut/resolve-monitor-config
                 {:execution/worktree-path "/tmp/repo"
                  :execution/self-author "miniforge[bot]"
                  :config {:pr-monitor/poll-interval-ms 15000}} nil nil nil)]
        (is (= 15000 (:poll-interval-ms cfg)))
        (is (= "miniforge[bot]" (:self-author cfg)))
        (is (= 10 (:max-total-fix-attempts-per-pr cfg)))))))
