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

(ns ai.miniforge.dag-executor.scheduler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.scheduler :as sut]
   [ai.miniforge.dag-executor.parallel :as parallel]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

;; The scheduler hard-codes :implementing as the post-:ready status in
;; `dispatch-task-start` (the kernel profile doesn't include :implementing —
;; it uses :running). Any caller of schedule-iteration must therefore supply
;; a profile that has :implementing. We use this profile across the tests
;; below. :completed is the single success-terminal status (mark-completed!
;; resolves the success status from the set, so keeping it singleton makes
;; the resolution deterministic). :merged is reachable from :completed and
;; mark-merged! will use it directly.
(def ^:private dag-task-profile
  {:profile/id :dag-test
   :task-statuses [:pending :ready :implementing :running :completed :failed :merged :skipped]
   :terminal-statuses [:completed :failed :skipped :merged]
   :success-terminal-statuses [:completed]
   :valid-transitions {:pending     [:ready :skipped]
                       :ready       [:implementing :running :skipped]
                       :implementing [:running :completed :failed :merged]
                       :running     [:completed :failed]
                       :completed   [:merged]
                       :failed      []
                       :skipped     []
                       :merged      []}
   :event-mappings {:started   {:type :transition :to :running}
                    :completed {:type :complete    :to :completed}
                    :failed    {:type :fail        :reason :failed}}})

(defn- run-state-with
  "Build a run state with a flat task list. Each task-def is
   {:task/id uuid :task/deps #{...}}. Optional :budget propagates."
  [task-defs & {:keys [budget]}]
  (let [tasks (into {}
                    (for [{:keys [task/id task/deps]} task-defs]
                      [id (state/create-task-state id (or deps #{})
                                                   :state-profile dag-task-profile)]))]
    (state/create-run-state (random-uuid) tasks
                            :budget budget
                            :state-profile dag-task-profile)))

(defn- run-atom-with
  [task-defs & opts]
  (state/create-run-atom (apply run-state-with task-defs opts)))

(defn- task-def
  ([id] (task-def id #{}))
  ([id deps] {:task/id id :task/deps deps}))

(defn- ctx
  "Build a scheduler context with a no-op executor by default."
  [& {:as overrides}]
  (sut/create-scheduler-context :max-parallel    (get overrides :max-parallel 4)
                                :execute-task-fn (get overrides :execute-task-fn (fn [_ _] {:success? true}))
                                :logger          (get overrides :logger nil)))

;------------------------------------------------------------------------------ Layer 1
;; budget-exhausted?

(deftest budget-exhausted?-no-budget-test
  (testing "Run without :run/config :budget never exhausts"
    (let [rs (run-state-with [(task-def (random-uuid))])]
      (is (nil? (sut/budget-exhausted? rs))))))

(deftest budget-exhausted?-tokens-test
  (testing "Total tokens at or above max-tokens exhausts"
    (let [rs (-> (run-state-with [] :budget {:max-tokens 100})
                 (assoc :run/metrics {:total-tokens 100 :total-cost-usd 0.0
                                      :total-duration-ms 0}))]
      (is (true? (sut/budget-exhausted? rs))))
    ;; The fn returns nil when within budget (it's a `(when budget (or ...))`),
    ;; not false — which is fine for the truthy-check callers do.
    (let [rs (-> (run-state-with [] :budget {:max-tokens 100})
                 (assoc :run/metrics {:total-tokens 99 :total-cost-usd 0.0
                                      :total-duration-ms 0}))]
      (is (not (sut/budget-exhausted? rs))))))

(deftest budget-exhausted?-cost-test
  (testing "Total cost at or above max-cost-usd exhausts"
    (let [rs (-> (run-state-with [] :budget {:max-cost-usd 1.0})
                 (assoc :run/metrics {:total-tokens 0 :total-cost-usd 1.0
                                      :total-duration-ms 0}))]
      (is (true? (sut/budget-exhausted? rs))))))

(deftest budget-exhausted?-duration-test
  (testing "Total duration at or above max-duration-ms exhausts"
    (let [rs (-> (run-state-with [] :budget {:max-duration-ms 60000})
                 (assoc :run/metrics {:total-tokens 0 :total-cost-usd 0.0
                                      :total-duration-ms 60000}))]
      (is (true? (sut/budget-exhausted? rs))))))

(deftest budget-exhausted?-multi-axis-test
  (testing "Any axis exceeded exhausts the budget"
    (let [rs (-> (run-state-with [] :budget {:max-tokens 100
                                             :max-cost-usd 1.0
                                             :max-duration-ms 60000})
                 (assoc :run/metrics {:total-tokens 50 :total-cost-usd 0.5
                                      :total-duration-ms 60000}))]
      (is (true? (sut/budget-exhausted? rs))))))

;------------------------------------------------------------------------------ Layer 1
;; should-checkpoint?

(deftest should-checkpoint?-elapsed-past-interval-test
  (testing "Returns true when elapsed since last-checkpoint exceeds interval"
    (let [past (- (System/currentTimeMillis) 70000)]
      (is (true? (sut/should-checkpoint? {} past 60000))))))

(deftest should-checkpoint?-elapsed-under-interval-test
  (testing "Returns false when elapsed is less than interval"
    (let [recent (- (System/currentTimeMillis) 1000)]
      (is (false? (sut/should-checkpoint? {} recent 60000))))))

(deftest should-checkpoint?-nil-last-checkpoint-test
  (testing "Nil last-checkpoint-time treats elapsed as now (since epoch) — always true"
    (is (true? (sut/should-checkpoint? {} nil 60000)))))

;------------------------------------------------------------------------------ Layer 1
;; compute-ready-tasks (passthrough)

(deftest compute-ready-tasks-only-no-deps-test
  (testing "Only tasks with no unmet deps are ready"
    (let [a (random-uuid) b (random-uuid)
          rs (run-state-with [(task-def a #{}) (task-def b #{a})])]
      (is (= #{a} (sut/compute-ready-tasks rs))))))

(deftest compute-ready-tasks-after-completion-test
  (testing "After upstream reaches a success-terminal status, downstream becomes ready.
            Only success-terminal statuses count as 'dependency satisfied' — :merged
            is terminal but isn't success-terminal in our test profile, so we use
            mark-completed!."
    (let [a (random-uuid) b (random-uuid)
          run-atom (run-atom-with [(task-def a #{}) (task-def b #{a})])]
      (state/transition-task! run-atom a :ready nil)
      (state/transition-task! run-atom a :implementing nil)
      (state/mark-completed! run-atom a nil)
      (is (= #{b} (sut/compute-ready-tasks @run-atom))))))

;------------------------------------------------------------------------------ Layer 1
;; skip-dependent-tasks

(deftest skip-dependent-tasks-marks-direct-dep-skipped-test
  (testing "Direct dependents of the failed task transition to :skipped"
    (let [a (random-uuid) b (random-uuid)
          rs (run-state-with [(task-def a #{}) (task-def b #{a})])
          updated (sut/skip-dependent-tasks rs a nil)]
      (is (= :skipped (get-in updated [:run/tasks b :task/status])))
      (is (= {:due-to-failure a}
             (get-in updated [:run/tasks b :task/skip-reason])))
      (is (contains? (:run/skipped updated) b)))))

(deftest skip-dependent-tasks-transitive-test
  (testing "Transitive dependents (b depends on a, c depends on b) are also skipped"
    (let [a (random-uuid) b (random-uuid) c (random-uuid)
          rs (run-state-with [(task-def a #{})
                              (task-def b #{a})
                              (task-def c #{b})])
          updated (sut/skip-dependent-tasks rs a nil)]
      (is (= :skipped (get-in updated [:run/tasks b :task/status])))
      (is (= :skipped (get-in updated [:run/tasks c :task/status]))))))

(deftest skip-dependent-tasks-no-dependents-test
  (testing "When no tasks depend on the failed one, run-state is unchanged"
    (let [a (random-uuid) b (random-uuid)
          rs (run-state-with [(task-def a #{}) (task-def b #{})])
          updated (sut/skip-dependent-tasks rs a nil)]
      (is (= :pending (get-in updated [:run/tasks b :task/status])))
      (is (empty? (:run/skipped updated))))))

(deftest skip-dependent-tasks-leaves-non-pending-alone-test
  (testing "A dependent already in a non-pending state is not modified"
    ;; FSM restrictions prevent :pending → :failed directly, so we set b's
    ;; status by hand to model "this dependent is already marked done by
    ;; some other path" — the branch under test reads :task/status and
    ;; only acts when it equals :pending.
    (let [a (random-uuid) b (random-uuid)
          rs (-> (run-state-with [(task-def a #{}) (task-def b #{a})])
                 (assoc-in [:run/tasks b :task/status] :failed))
          updated (sut/skip-dependent-tasks rs a nil)]
      (is (= :failed (get-in updated [:run/tasks b :task/status])))
      (is (not (contains? (:run/skipped updated) b))))))

;------------------------------------------------------------------------------ Layer 1
;; create-dispatch-event

(deftest create-dispatch-event-defaults-test
  (testing "Required fields are populated; optional pr-event/error are nil"
    (let [tid (random-uuid)
          ev (sut/create-dispatch-event tid :start)]
      (is (uuid? (:event/id ev)))
      (is (= :task-dispatch (:event/type ev)))
      (is (= :start (:event/action ev)))
      (is (= tid (:event/task-id ev)))
      (is (nil? (:event/pr-event ev)))
      (is (nil? (:event/error ev)))
      (is (inst? (:event/timestamp ev))))))

(deftest create-dispatch-event-with-pr-event-and-error-test
  (testing "Optional :pr-event and :error are stored on the event"
    (let [ev (sut/create-dispatch-event (random-uuid) :failed
                                        :pr-event {:type :ci-failed}
                                        :error {:type :timeout})]
      (is (= {:type :ci-failed} (:event/pr-event ev)))
      (is (= {:type :timeout}   (:event/error ev))))))

;------------------------------------------------------------------------------ Layer 1
;; dispatch-task-start

(deftest dispatch-task-start-success-transitions-pending-to-implementing-test
  (testing "Task moves from :pending → :ready → :implementing and ok event is returned"
    (let [tid (random-uuid)
          run-atom (run-atom-with [(task-def tid)])
          ret (sut/dispatch-task-start run-atom tid (ctx))]
      (is (result/ok? ret))
      (is (= :implementing (get-in @run-atom [:run/tasks tid :task/status])))
      (is (= :start (-> ret :data :event/action)))
      (is (= tid (-> ret :data :event/task-id))))))

(deftest dispatch-task-start-not-pending-returns-error-test
  (testing "If a task is already running, dispatch-task-start fails on the
            first transition and returns the error"
    (let [tid (random-uuid)
          run-atom (run-atom-with [(task-def tid)])
          _ (state/transition-task! run-atom tid :ready nil)
          _ (state/transition-task! run-atom tid :implementing nil)
          ret (sut/dispatch-task-start run-atom tid (ctx))]
      (is (result/err? ret)))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-state-helper

(deftest resolve-state-helper-known-keys-test
  (testing "All four documented state-helper keys resolve to the matching state fn"
    (is (= state/max-ci-retries-exceeded?
           (sut/resolve-state-helper :max-ci-retries-exceeded?)))
    (is (= state/max-fix-iterations-exceeded?
           (sut/resolve-state-helper :max-fix-iterations-exceeded?)))
    (is (= state/increment-ci-retries
           (sut/resolve-state-helper :increment-ci-retries)))
    (is (= state/increment-fix-iterations
           (sut/resolve-state-helper :increment-fix-iterations)))))

(deftest resolve-state-helper-unknown-test
  (testing "Unknown keys return nil rather than throwing"
    (is (nil? (sut/resolve-state-helper :totally-unknown)))
    (is (nil? (sut/resolve-state-helper nil)))))

;------------------------------------------------------------------------------ Layer 1
;; handle-task-event — dispatched against the kernel profile

(deftest handle-task-event-unknown-action-test
  (testing "An action not in the profile's :event-mappings returns :unknown-event"
    (let [tid (random-uuid)
          run-atom (run-atom-with [(task-def tid)])
          ret (sut/handle-task-event run-atom
                                     (sut/create-dispatch-event tid :totally-unknown)
                                     (ctx))]
      (is (result/err? ret))
      (is (= :unknown-event (-> ret :error :code))))))

(deftest handle-task-event-started-transitions-to-running-test
  (testing "Test profile (dag-task-profile) maps :started → :transition :running"
    (let [tid (random-uuid)
          run-atom (run-atom-with [(task-def tid)])
          _ (state/transition-task! run-atom tid :ready nil)
          ret (sut/handle-task-event run-atom
                                     (sut/create-dispatch-event tid :started)
                                     (ctx))]
      (is (result/ok? ret))
      (is (= :running (get-in @run-atom [:run/tasks tid :task/status]))))))

(deftest handle-task-event-completed-marks-completed-test
  (testing "Test profile maps :completed → :complete :completed (success terminal)"
    (let [tid (random-uuid)
          run-atom (run-atom-with [(task-def tid)])
          _ (state/transition-task! run-atom tid :ready nil)
          _ (state/transition-task! run-atom tid :running nil)
          ret (sut/handle-task-event run-atom
                                     (sut/create-dispatch-event tid :completed)
                                     (ctx))]
      (is (result/ok? ret))
      (is (= :completed (get-in @run-atom [:run/tasks tid :task/status]))))))

(deftest handle-task-event-failed-marks-failed-test
  (testing "Test profile maps :failed → :fail with reason :failed"
    (let [tid (random-uuid)
          run-atom (run-atom-with [(task-def tid)])
          _ (state/transition-task! run-atom tid :ready nil)
          _ (state/transition-task! run-atom tid :running nil)
          ret (sut/handle-task-event run-atom
                                     (sut/create-dispatch-event tid :failed
                                                                 :error {:type :timeout})
                                     (ctx))]
      (is (result/ok? ret))
      (is (= :failed (get-in @run-atom [:run/tasks tid :task/status])))
      (is (contains? (:run/failed @run-atom) tid)))))

;------------------------------------------------------------------------------ Layer 2
;; create-scheduler-context

(deftest create-scheduler-context-defaults-test
  (testing "Defaults: max-parallel 4, checkpoint-interval-ms 60s, fresh lock-pool,
            last-checkpoint-time atom is set to now-ish"
    (let [c (sut/create-scheduler-context)]
      (is (= 4 (:max-parallel c)))
      (is (= 60000 (:checkpoint-interval-ms c)))
      (is (some? (:lock-pool c)))
      (is (instance? clojure.lang.Atom (:last-checkpoint-time c)))
      (is (>= @(:last-checkpoint-time c)
              (- (System/currentTimeMillis) 5000))))))

(deftest create-scheduler-context-overrides-test
  (testing "Custom :max-parallel, :checkpoint-interval-ms, and :lock-pool flow through"
    (let [pool (parallel/create-lock-pool :max-repo-writes 2)
          c (sut/create-scheduler-context :max-parallel 8
                                          :checkpoint-interval-ms 5000
                                          :lock-pool pool)]
      (is (= 8 (:max-parallel c)))
      (is (= 5000 (:checkpoint-interval-ms c)))
      (is (identical? pool (:lock-pool c))))))

;------------------------------------------------------------------------------ Layer 2
;; schedule-iteration

(deftest schedule-iteration-empty-run-completes-test
  (testing "Run with no tasks reports :all-complete on first iteration"
    (let [run-atom (run-atom-with [])
          ret (sut/schedule-iteration run-atom (ctx))]
      (is (false? (:continue? ret)))
      (is (= :all-complete (:termination ret))))))

(deftest schedule-iteration-budget-exhausted-pauses-test
  (testing "Iteration with exhausted budget pauses the run, returns :budget-exhausted"
    (let [tid (random-uuid)
          run-state (-> (run-state-with [(task-def tid)] :budget {:max-tokens 100})
                        (assoc :run/metrics {:total-tokens 100 :total-cost-usd 0.0
                                             :total-duration-ms 0}))
          run-atom (state/create-run-atom run-state)
          ret (sut/schedule-iteration run-atom (ctx))]
      (is (false? (:continue? ret)))
      (is (= :budget-exhausted (:termination ret)))
      (is (= :paused (get-in ret [:run-state :run/status]))))))

(deftest schedule-iteration-dispatches-ready-tasks-test
  (testing "First iteration with no running tasks dispatches up to max-parallel ready tasks"
    (let [a (random-uuid) b (random-uuid) c (random-uuid)
          run-atom (run-atom-with [(task-def a) (task-def b) (task-def c)])
          ret (sut/schedule-iteration run-atom (ctx :max-parallel 2))]
      (is (true? (:continue? ret)))
      ;; Exactly two tasks are dispatched (max-parallel = 2)
      (is (= 2 (count (:dispatched ret))))
      ;; All dispatched tasks are now :implementing
      (doseq [tid (:dispatched ret)]
        (is (= :implementing (get-in @run-atom [:run/tasks tid :task/status])))))))

(deftest schedule-iteration-respects-running-task-slots-test
  (testing "Already-running tasks count against max-parallel"
    (let [a (random-uuid) b (random-uuid) c (random-uuid)
          run-atom (run-atom-with [(task-def a) (task-def b) (task-def c)])
          ;; Pretend a is already running.
          _ (state/transition-task! run-atom a :ready nil)
          _ (state/transition-task! run-atom a :implementing nil)
          ;; Only one slot free under max-parallel 2.
          ret (sut/schedule-iteration run-atom (ctx :max-parallel 2))]
      (is (true? (:continue? ret)))
      (is (= 1 (count (:dispatched ret)))))))

(deftest schedule-iteration-no-ready-tasks-still-continues-test
  (testing "When no tasks are ready (all have unmet deps) iteration still continues"
    (let [a (random-uuid) b (random-uuid)
          ;; Both tasks depend on each other (cycle) — neither ready.
          run-atom (run-atom-with [(task-def a #{b}) (task-def b #{a})])
          ret (sut/schedule-iteration run-atom (ctx))]
      (is (true? (:continue? ret)))
      (is (empty? (:dispatched ret))))))

(deftest schedule-iteration-invokes-execute-task-fn-test
  (testing "execute-task-fn is invoked for every dispatched task"
    (let [executed (atom #{})
          a (random-uuid) b (random-uuid)
          run-atom (run-atom-with [(task-def a) (task-def b)])
          c (sut/create-scheduler-context
              :max-parallel 4
              :execute-task-fn (fn [tid _ctx] (swap! executed conj tid)))]
      (sut/schedule-iteration run-atom c)
      (is (= #{a b} @executed)))))

(deftest schedule-iteration-all-terminal-marks-final-status-test
  (testing "When every task is in a success-terminal state, iteration computes
            :completed and stops. Using mark-completed! (not mark-merged!) so the
            task counts toward success-terminal under the test profile."
    (let [a (random-uuid)
          run-atom (run-atom-with [(task-def a)])
          _ (state/transition-task! run-atom a :ready nil)
          _ (state/transition-task! run-atom a :implementing nil)
          _ (state/mark-completed! run-atom a nil)
          ret (sut/schedule-iteration run-atom (ctx))]
      (is (false? (:continue? ret)))
      (is (= :all-complete (:termination ret)))
      (is (= :completed (get-in ret [:run-state :run/status]))))))

;------------------------------------------------------------------------------ Layer 2
;; pause-scheduler / resume-scheduler

(deftest pause-scheduler-sets-paused-status-test
  (testing "pause-scheduler transitions run to :paused and returns the new state"
    (let [run-atom (run-atom-with [(task-def (random-uuid))])
          _ (swap! run-atom state/transition-run :running)
          paused (sut/pause-scheduler run-atom nil)]
      (is (= :paused (:run/status paused)))
      (is (= :paused (:run/status @run-atom))))))

(deftest resume-scheduler-no-op-when-not-paused-test
  (testing "resume-scheduler is a no-op when the run is not in :paused status"
    (let [run-atom (run-atom-with [(task-def (random-uuid))])
          _ (swap! run-atom state/transition-run :running)
          ret (sut/resume-scheduler run-atom (ctx))]
      ;; resume-scheduler returns nil for non-paused runs (the `when` form
      ;; evaluates to nil) and does NOT call run-scheduler.
      (is (nil? ret))
      (is (= :running (:run/status @run-atom))))))
