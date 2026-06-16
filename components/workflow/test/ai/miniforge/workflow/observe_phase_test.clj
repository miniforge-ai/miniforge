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

;; Named test constants — all duplicated literals centralised here (rule 006).
;; Values are arbitrary stubs; tests care about plumbing, not specific numbers.

(def ^:private test-poll-interval-ms
  "Stub poll interval used across observe-phase tests. Value is arbitrary;
   tests care about plumbing, not the specific ms figure."
  60000)

(def ^:private test-abandon-after-hours
  "Stub abandon window used across observe-phase tests."
  72)

(def ^:private test-deref-timeout-ms
  "Upper bound for deref calls in async observe-phase tests. 2s is ample
   headroom; tests that need the loop to *not* run synchronously will see
   it complete far sooner."
  2000)

(def ^:private test-max-total-fix-attempts
  "Stub max-total-fix-attempts-per-pr used in resolve-monitor-config-test.
   Both the mock and the assertion reference this constant so they stay in sync."
  10)

(def ^:private test-override-poll-interval-ms
  "Stub poll-interval-ms context override used in resolve-monitor-config-test.
   Referenced in both the context map and the assertion."
  15000)

(def ^:private three-days-in-seconds
  "72h expressed as seconds — the default observe-phase budget.time-seconds value."
  259200)

(def ^:private sample-pr
  ;; Single-PR release key shape (:pr-number/:pr-url/:branch/:commit-sha),
  ;; as built by phase-software-factory/release.clj. Used in tests where the
  ;; specific key shape is not under test.
  {:pr-number 42 :pr-url "https://github.com/o/r/pull/42" :branch "mf/x" :commit-sha "deadbeef"})

;------------------------------------------------------------------------------ Layer 0
;; Private helper tests: pr-url->repo, pr-info->worklist-entry,
;; remote-origin-url, try-persist-worklist!, resolve-pr-infos,
;; resolve-monitor-config, and config loading.

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

(deftest pr-info->worklist-entry-test
  (let [fixed-now (java.util.Date. 0)]
    (testing ":pr/url + :pr/number key shape (DAG release shape)"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/42" :pr/number 42}
                   test-poll-interval-ms test-abandon-after-hours fixed-now)]
        (is (= "https://github.com/org/repo/pull/42" (:pr/url entry)))
        (is (= 42 (:pr/number entry)))
        (is (= "org/repo" (:pr/repo entry)))
        (is (= fixed-now (:pr/added-at entry)))
        (is (= 60 (:pr/poll-interval entry)))
        (is (= test-abandon-after-hours (:pr/abandon-after-hours entry)))))

    (testing ":pr-url + :pr-number key shape (single-PR release shape)"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr-url "https://github.com/org/repo/pull/7" :pr-number 7}
                   30000 48 fixed-now)]
        (is (= "https://github.com/org/repo/pull/7" (:pr/url entry)))
        (is (= 7 (:pr/number entry)))
        (is (= "org/repo" (:pr/repo entry)))))

    (testing "missing url returns nil"
      (is (nil? (#'sut/pr-info->worklist-entry
                 {:pr/number 1}
                 test-poll-interval-ms test-abandon-after-hours fixed-now))))

    (testing "missing number returns nil"
      (is (nil? (#'sut/pr-info->worklist-entry
                 {:pr/url "https://github.com/org/repo/pull/1"}
                 test-poll-interval-ms test-abandon-after-hours fixed-now))))

    (testing "non-GitHub URL with no parseable repo and no explicit :pr/repo returns nil"
      (is (nil? (#'sut/pr-info->worklist-entry
                 {:pr/url "https://notgithub.example.com/pr/1" :pr/number 1}
                 test-poll-interval-ms test-abandon-after-hours fixed-now))))

    (testing "explicit :pr/repo overrides URL parsing"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://notgithub.example.com/pr/1"
                    :pr/number 1
                    :pr/repo "myorg/myrepo"}
                   test-poll-interval-ms test-abandon-after-hours fixed-now)]
        (is (= "myorg/myrepo" (:pr/repo entry)))))

    (testing "nil poll-interval-ms omits :pr/poll-interval key"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}
                   nil test-abandon-after-hours fixed-now)]
        (is (some? entry))
        (is (not (contains? entry :pr/poll-interval)))))

    (testing "nil abandon-after-hours omits :pr/abandon-after-hours key"
      (let [entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}
                   test-poll-interval-ms nil fixed-now)]
        (is (some? entry))
        (is (not (contains? entry :pr/abandon-after-hours)))))

    (testing "timestamp comes from the now argument, not wall clock"
      (let [t     (java.util.Date. 123456789)
            entry (#'sut/pr-info->worklist-entry
                   {:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}
                   test-poll-interval-ms test-abandon-after-hours t)]
        (is (= t (:pr/added-at entry)))))))

(deftest remote-origin-url-test
  (testing "blank worktree-path returns nil without shelling out"
    (is (nil? (#'sut/remote-origin-url ""))))
  (testing "nil worktree-path returns nil without shelling out"
    (is (nil? (#'sut/remote-origin-url nil))))
  (testing "whitespace-only worktree-path returns nil without shelling out"
    (is (nil? (#'sut/remote-origin-url "   ")))))

(deftest try-persist-worklist!-never-throws-test
  ;; try-persist-worklist! must NEVER throw regardless of what the downstream
  ;; persistence call does. All four sub-cases exercise this contract.
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
          (is (nil? (#'sut/try-persist-worklist!
                     {:worktree-path       "/tmp/repo"
                      :poll-interval-ms    test-poll-interval-ms
                      :abandon-after-hours test-abandon-after-hours}
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
                     {:worktree-path       "/tmp/repo"
                      :poll-interval-ms    test-poll-interval-ms
                      :abandon-after-hours test-abandon-after-hours}
                     "bot" [] nil))))))

    (testing "blank worktree-path skips the persist block without throwing"
      ;; remote-origin-url returns nil for blank path → the when block is never entered
      (with-redefs [clock/now-ms (constantly 0)]
        (is (nil? (#'sut/try-persist-worklist!
                   {:worktree-path       ""
                    :poll-interval-ms    test-poll-interval-ms
                    :abandon-after-hours test-abandon-after-hours}
                   "bot" [] nil)))))

    (testing "persist-worklist! returning a success result produces nil with no warning"
      (with-redefs-fn
        {remote-url-var                    (fn [_] "https://github.com/org/repo.git")
         #'pr-lifecycle/worklist-repo-key  (fn [_] "org/repo")
         #'pr-lifecycle/worklist-path      (fn [_ _] "/tmp/wl.edn")
         #'pr-lifecycle/persist-worklist!  (fn [_ _] (schema/success :worklist {}))
         #'config/miniforge-home           (constantly "/tmp")
         #'clock/now-ms                    (constantly 0)}
        (fn []
          (is (nil? (#'sut/try-persist-worklist!
                     {:worktree-path       "/tmp/repo"
                      :poll-interval-ms    test-poll-interval-ms
                      :abandon-after-hours test-abandon-after-hours}
                     "bot"
                     [{:pr/url "https://github.com/org/repo/pull/1" :pr/number 1}]
                     nil))))))))

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

(deftest default-config-loaded-from-edn-test
  (testing "observe phase defaults come from resource config"
    (is (= :default (:agent sut/default-config)))
    (is (= three-days-in-seconds (get-in sut/default-config [:budget :time-seconds])))
    (is (= [] (:gates sut/default-config)))))

(deftest resolve-monitor-config-test
  (testing "context overrides are merged over shared monitor defaults"
    (with-redefs [sut/load-monitor-defaults
                  (fn []
                    {:poll-interval-ms            test-poll-interval-ms
                     :self-author                 nil
                     :max-fix-attempts-per-comment 3
                     :max-total-fix-attempts-per-pr test-max-total-fix-attempts
                     :abandon-after-hours         test-abandon-after-hours})]
      (let [cfg (#'sut/resolve-monitor-config
                 {:execution/worktree-path "/tmp/repo"
                  :execution/self-author   "miniforge[bot]"
                  :config {:pr-monitor/poll-interval-ms test-override-poll-interval-ms}}
                 nil nil nil)]
        (is (= test-override-poll-interval-ms (:poll-interval-ms cfg)))
        (is (= "miniforge[bot]" (:self-author cfg)))
        (is (= test-max-total-fix-attempts (:max-total-fix-attempts-per-pr cfg)))))))

;------------------------------------------------------------------------------ Layer 1
;; enter-observe integration tests: detach contract, skip paths, persistence wiring.

(deftest enter-observe-detaches-monitor-test
  ;; Observe must NOT block the workflow thread — it starts the monitor on a
  ;; detached future and returns :monitoring immediately. A synchronous loop
  ;; could block for up to :abandon-after-hours.
  (testing "enter-observe returns :monitoring immediately without waiting on the monitor loop"
    (let [loop-released    (promise)
          loop-entered     (promise)
          monitor-sentinel (Object.)]
      (with-redefs [sut/resolve-monitor-config    (fn [& _] {:self-author "miniforge[bot]"})
                    pr-lifecycle/create-pr-monitor (fn [_] monitor-sentinel)
                    pr-lifecycle/run-pr-monitor-loop
                    (fn [monitor author]
                      (deliver loop-entered {:monitor monitor :author author})
                      @loop-released
                      {:comments-received 0})]
        (let [ctx    (sut/enter-observe {:execution/dag-pr-infos [sample-pr]})
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
          (is (= {:monitor monitor-sentinel :author "miniforge[bot]"}
                 (deref loop-entered test-deref-timeout-ms :timed-out)))
          (deliver loop-released true)
          (is (= {:comments-received 0}
                 (deref (:execution/pr-monitor-future ctx) test-deref-timeout-ms :timed-out))))))))

(deftest enter-observe-skips-when-no-prs-test
  (testing "no PRs to observe -> :skipped, no monitor future"
    (let [ctx (sut/enter-observe {})]
      (is (= :completed (get-in ctx [:phase :status])))
      (is (= :skipped (get-in ctx [:phase :result :output :observe/status])))
      (is (nil? (:execution/pr-monitor-future ctx))))))

(deftest enter-observe-calls-persist-worklist!-test
  ;; Verify persistence wiring: when pr-infos are present and self-author resolves,
  ;; persist-worklist! is called once with an entry that carries the PR data.
  ;; Both key shapes (single-PR :pr-url/:pr-number and DAG :pr/url/:pr/number) are
  ;; tested to confirm both paths reach the persistence call correctly.
  (let [remote-url-var      (ns-resolve 'ai.miniforge.workflow.observe-phase
                                        'remote-origin-url)
        resolve-monitor-var (ns-resolve 'ai.miniforge.workflow.observe-phase
                                        'resolve-monitor-config)]

    (testing "single-PR key shape (:pr-url/:pr-number) → persist-worklist! called with correct entry"
      (let [persist-calls (atom [])]
        (with-redefs-fn
          {remote-url-var              (fn [_] "https://github.com/org/repo.git")
           resolve-monitor-var         (fn [& _]
                                         {:self-author         "miniforge[bot]"
                                          :worktree-path       "/tmp/repo"
                                          :poll-interval-ms    test-poll-interval-ms
                                          :abandon-after-hours test-abandon-after-hours})
           #'pr-lifecycle/worklist-repo-key   (fn [_] "org/repo")
           #'pr-lifecycle/worklist-path       (fn [_ _] "/tmp/wl.edn")
           #'pr-lifecycle/persist-worklist!   (fn [path entry]
                                                (swap! persist-calls conj {:path path :entry entry})
                                                (schema/success :worklist {}))
           #'config/miniforge-home            (constantly "/tmp")
           #'clock/now-ms                     (constantly 0)
           #'pr-lifecycle/create-pr-monitor   (fn [_] ::sentinel)
           #'pr-lifecycle/run-pr-monitor-loop (fn [& _] {:done true})}
          (fn []
            (let [ctx (sut/enter-observe {:execution/dag-pr-infos [sample-pr]})]
              (is (= 1 (count @persist-calls))
                  "persist-worklist! must be called exactly once")
              (let [{:keys [path entry]} (first @persist-calls)]
                (is (= "/tmp/wl.edn" path)
                    "path must come from the worklist-path helper")
                (is (= "org/repo" (:worklist/repo-key entry))
                    "entry must carry the repo key derived from the git remote")
                (is (= 1 (count (:worklist/prs entry)))
                    "one pr-info in context → one PR entry in the worklist")
                (let [pr-entry (first (:worklist/prs entry))]
                  (is (= (:pr-url sample-pr) (:pr/url pr-entry))
                      "PR url must be propagated to the worklist entry")
                  (is (= (:pr-number sample-pr) (:pr/number pr-entry))
                      "PR number must be propagated to the worklist entry")))
              ;; Result shape must be :monitoring on the persistence success path
              (is (= :monitoring (get-in ctx [:phase :result :output :observe/status]))
                  "phase result must be :monitoring on the persistence success path")
              (is (true? (get-in ctx [:phase :result :output :observe/monitor-detached?]))
                  ":monitor-detached? must be true on the persistence success path"))))))

    (testing "DAG namespaced key shape (:pr/url/:pr/number) → persist-worklist! called with correct entry"
      (let [dag-pr        {:pr/url    "https://github.com/org/repo/pull/99"
                           :pr/number 99
                           :pr/repo   "org/repo"}
            persist-calls (atom [])]
        (with-redefs-fn
          {remote-url-var              (fn [_] "https://github.com/org/repo.git")
           resolve-monitor-var         (fn [& _]
                                         {:self-author         "miniforge[bot]"
                                          :worktree-path       "/tmp/repo"
                                          :poll-interval-ms    test-poll-interval-ms
                                          :abandon-after-hours test-abandon-after-hours})
           #'pr-lifecycle/worklist-repo-key   (fn [_] "org/repo")
           #'pr-lifecycle/worklist-path       (fn [_ _] "/tmp/wl.edn")
           #'pr-lifecycle/persist-worklist!   (fn [path entry]
                                                (swap! persist-calls conj {:path path :entry entry})
                                                (schema/success :worklist {}))
           #'config/miniforge-home            (constantly "/tmp")
           #'clock/now-ms                     (constantly 0)
           #'pr-lifecycle/create-pr-monitor   (fn [_] ::sentinel)
           #'pr-lifecycle/run-pr-monitor-loop (fn [& _] {:done true})}
          (fn []
            (let [ctx (sut/enter-observe {:execution/dag-pr-infos [dag-pr]})]
              (is (= 1 (count @persist-calls))
                  "persist-worklist! must be called once for the DAG namespaced shape")
              (let [pr-entry (first (:worklist/prs (:entry (first @persist-calls))))]
                (is (= (:pr/url dag-pr) (:pr/url pr-entry))
                    "namespaced :pr/url must be propagated to the worklist entry")
                (is (= (:pr/number dag-pr) (:pr/number pr-entry))
                    "namespaced :pr/number must be propagated to the worklist entry"))
              (is (= :monitoring (get-in ctx [:phase :result :output :observe/status]))
                  "phase result must be :monitoring for the DAG namespaced shape path"))))))))

(deftest enter-observe-empty-prs-skips-persistence-test
  ;; When there are no pr-infos, enter-observe returns :skipped before reaching
  ;; the persistence call. persist-worklist! must never be invoked.
  (testing "no pr-infos → persist-worklist! is never invoked"
    (let [persist-call-count (atom 0)]
      (with-redefs [pr-lifecycle/persist-worklist! (fn [& _]
                                                     (swap! persist-call-count inc)
                                                     (schema/success :worklist {}))]
        (sut/enter-observe {})
        (is (zero? @persist-call-count)
            "persist-worklist! must not be called when there are no PRs to observe")))))

(deftest enter-observe-persist-failure-result-shape-unchanged-test
  ;; try-persist-worklist! is best-effort: a failure return from persist-worklist!
  ;; logs a warning but must not alter the phase result shape. The caller sees
  ;; :monitoring with :monitor-detached? true regardless.
  (testing "persist-worklist! returning a failure anomaly → phase result still :monitoring"
    (let [remote-url-var      (ns-resolve 'ai.miniforge.workflow.observe-phase
                                          'remote-origin-url)
          resolve-monitor-var (ns-resolve 'ai.miniforge.workflow.observe-phase
                                          'resolve-monitor-config)]
      (with-redefs-fn
        {remote-url-var              (fn [_] "https://github.com/org/repo.git")
         resolve-monitor-var         (fn [& _]
                                       {:self-author         "miniforge[bot]"
                                        :worktree-path       "/tmp/repo"
                                        :poll-interval-ms    test-poll-interval-ms
                                        :abandon-after-hours test-abandon-after-hours})
         #'pr-lifecycle/worklist-repo-key   (fn [_] "org/repo")
         #'pr-lifecycle/worklist-path       (fn [_ _] "/tmp/wl.edn")
         #'pr-lifecycle/persist-worklist!   (fn [_ _] (schema/failure :worklist "disk full"))
         #'config/miniforge-home            (constantly "/tmp")
         #'clock/now-ms                     (constantly 0)
         #'pr-lifecycle/create-pr-monitor   (fn [_] ::sentinel)
         #'pr-lifecycle/run-pr-monitor-loop (fn [& _] {:done true})}
        (fn []
          (let [ctx    (sut/enter-observe {:execution/dag-pr-infos [sample-pr]})
                result (get-in ctx [:phase :result])]
            (is (= :completed (get-in ctx [:phase :status]))
                "persist failure must not set phase status to :failed")
            (is (= :monitoring (get-in result [:output :observe/status]))
                "observe/status must remain :monitoring despite persist failure")
            (is (true? (get-in result [:output :observe/monitor-detached?]))
                ":monitor-detached? must remain true despite persist failure")
            (is (future? (:execution/pr-monitor-future ctx))
                "monitor future must still be created despite persist failure")))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; REPL: run all observe-phase tests in isolation
  (clojure.test/run-tests 'ai.miniforge.workflow.observe-phase-test)

  ;; Run only the persistence-wiring tests
  (clojure.test/test-vars
   [#'enter-observe-calls-persist-worklist!-test
    #'enter-observe-empty-prs-skips-persistence-test
    #'enter-observe-persist-failure-result-shape-unchanged-test])

  :leave-this-here)
