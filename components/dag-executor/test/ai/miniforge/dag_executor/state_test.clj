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

(ns ai.miniforge.dag-executor.state-test
  "Tests for DAG task state management and event emission wiring."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.state-profile :as profiles]
   [ai.miniforge.dag-executor.state :as state]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.event-stream.interface :as es])
  (:import
   (java.util.concurrent CountDownLatch TimeUnit)))

(def ^:private concurrent-terminal-transition-attempts
  "Number of repeated races used to make the terminal-transition regression
   test sensitive to CAS retry bugs without making the smoke suite expensive."
  100)

(def ^:private latch-timeout-seconds
  "Maximum time a concurrency test may wait for a latch before failing fast.
   Five seconds leaves room for CI scheduling stalls without hiding deadlocks."
  5)

(def ^:private future-timeout-ms
  "Maximum time to wait for each terminal-transition future. It should finish
   immediately after the start latch opens; this bound turns hangs into failures."
  5000)

(def ^:private future-timeout-result
  "Sentinel returned by `deref` when a terminal-transition future hangs."
  ::future-timeout)

(defn- state-private
  [sym]
  (var-get (ns-resolve 'ai.miniforge.dag-executor.state sym)))

(defn- transition-task-in-run
  [run-state task-id new-status task-update-fn]
  ((state-private 'transition-task-in-run) run-state task-id new-status task-update-fn))

(defn- terminal-transition-in-run
  [run-state task-id new-status mark-run-fn task-update-fn]
  ((state-private 'terminal-transition-in-run)
   run-state
   task-id
   new-status
   mark-run-fn
   task-update-fn))

(defn- running-run-state
  [task-id]
  (let [task         (state/create-task-state task-id #{} :state-profile :kernel)
        ready-task   (:data (state/transition-task task :ready))
        running-task (:data (state/transition-task ready-task :running))]
    (state/create-run-state (random-uuid) {task-id running-task} :state-profile :kernel)))

(defn- await-latch!
  [latch timeout-data]
  (when-not (.await latch latch-timeout-seconds TimeUnit/SECONDS)
    (throw (ex-info "timed out waiting for concurrency test latch" timeout-data))))

(defn- terminal-transition-future
  [ready-latch start-latch transition transition-fn]
  (future
    (.countDown ready-latch)
    (await-latch! start-latch {:transition transition})
    (transition-fn)))

;; ============================================================================
;; State transition tests
;; ============================================================================

(deftest transition-task-pure-test
  (testing "valid transition returns ok result"
    (let [task (state/create-task-state (random-uuid) #{})
          result (state/transition-task task :ready)]
      (is (result/ok? result))
      (is (= :ready (:task/status (:data result))))))

  (testing "invalid transition returns error"
    (let [task (state/create-task-state (random-uuid) #{})
          result (state/transition-task task :merged)]
      (is (result/err? result)))))

(deftest transition-task!-test
  (testing "atomically transitions task in run state"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (state/create-run-state (random-uuid) {task-id task})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (is (= :ready (get-in @run-atom [:run/tasks task-id :task/status])))))

  (testing "returns error for nonexistent task"
    (let [run (state/create-run-state (random-uuid) {})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom (random-uuid) :ready nil)]
      (is (result/err? result)))))

(deftest transition-task-in-run-test
  (testing "pure helper returns updated run state and transition metadata"
    (let [task-id    (random-uuid)
          task       (state/create-task-state task-id #{})
          run        (state/create-run-state (random-uuid) {task-id task})
          transition (transition-task-in-run run task-id :ready identity)
          data       (:data transition)]
      (is (result/ok? transition))
      (is (= :pending (:from-status data)))
      (is (= :ready (:to-status data)))
      (is (= :ready (get-in data [:run-state :run/tasks task-id :task/status])))))

  (testing "pure helper returns task-not-found without mutating run state"
    (let [task-id    (random-uuid)
          run        (state/create-run-state (random-uuid) {})
          transition (transition-task-in-run run task-id :ready identity)]
      (is (result/err? transition))
      (is (= :task-not-found (get-in transition [:error :code]))))))

(deftest terminal-transition-in-run-test
  (testing "terminal helper applies task update and run bucket update"
    (let [task-id    (random-uuid)
          error-info {:reason :test-error}
          run        (running-run-state task-id)
          transition (terminal-transition-in-run run
                                                 task-id
                                                 :failed
                                                 state/mark-task-failed
                                                 #(assoc % :task/error error-info))
          data       (:data transition)]
      (is (result/ok? transition))
      (is (= :running (:from-status data)))
      (is (= :failed (:to-status data)))
      (is (= :failed (get-in data [:run-state :run/tasks task-id :task/status])))
      (is (= error-info (get-in data [:run-state :run/tasks task-id :task/error])))
      (is (contains? (:run/failed (:run-state data)) task-id)))))

;; ============================================================================
;; Event emission wiring tests
;; ============================================================================

(deftest transition-task!-emits-event-test
  (testing "transition-task! emits task/state-changed when event-stream is configured"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          dag-id (random-uuid)
          task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (-> (state/create-run-state dag-id {task-id task})
                  (assoc-in [:run/config :event-stream] stream)
                  (assoc-in [:run/config :workflow-id] wf-id))
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (let [events (es/get-events stream)]
        (is (= 1 (count events)))
        (let [event (first events)]
          (is (= :task/state-changed (:event/type event)))
          (is (= dag-id (:dag/id event)))
          (is (= task-id (:task/id event)))
          (is (= :pending (:task/from-state event)))
          (is (= :ready (:task/to-state event))))))))

(deftest transition-task!-works-without-event-stream-test
  (testing "transition-task! works normally without event-stream in config"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (state/create-run-state (random-uuid) {task-id task})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (is (= :ready (get-in @run-atom [:run/tasks task-id :task/status]))))))

(deftest transition-task!-concurrent-terminal-transitions-test
  (testing "exactly one of two concurrent terminal transitions wins; atom state is consistent"
    (dotimes [_ concurrent-terminal-transition-attempts]
      (let [task-id     (random-uuid)
            task        (state/create-task-state task-id #{})
            run         (state/create-run-state (random-uuid) {task-id task})
            run-atom    (state/create-run-atom run)
            ;; assert setup so failures point at the precondition, not the race
            _           (is (result/ok? (state/transition-task! run-atom task-id :ready nil))
                            "setup: :pending → :ready must succeed")
            _           (is (result/ok? (state/transition-task! run-atom task-id :running nil))
                            "setup: :ready → :running must succeed")
            ;; two-phase barrier: both futures signal ready before the main thread
            ;; releases them, guaranteeing they are parked at start-latch simultaneously
            ready-latch (CountDownLatch. 2)
            start-latch (CountDownLatch. 1)
            f1          (terminal-transition-future
                         ready-latch
                         start-latch
                         :completed
                         #(state/transition-task! run-atom task-id :completed nil))
            f2          (terminal-transition-future
                         ready-latch
                         start-latch
                         :failed
                         #(state/transition-task! run-atom task-id :failed nil))
            ready?      (.await ready-latch latch-timeout-seconds TimeUnit/SECONDS)
            _           (is ready? "both futures must reach the barrier within 5s")
            _           (when-not ready?
                          (throw (ex-info "timed out waiting for futures to reach barrier"
                                          {:task-id task-id})))
            _           (.countDown start-latch)
            r1          (deref f1 future-timeout-ms future-timeout-result)
            r2          (deref f2 future-timeout-ms future-timeout-result)
            ;; Cancel stuck futures and fail fast so no threads accumulate
            _           (when (or (= future-timeout-result r1)
                                  (= future-timeout-result r2))
                          (future-cancel f1)
                          (future-cancel f2)
                          (throw (ex-info "future timed out waiting for terminal transition"
                                          {:task-id task-id
                                           :f1-timeout? (= future-timeout-result r1)
                                           :f2-timeout? (= future-timeout-result r2)})))
            final       (get-in @run-atom [:run/tasks task-id :task/status])]
        (is (contains? #{:completed :failed} final)
            "final status must be a valid terminal state")
        (is (or (and (result/ok? r1) (result/err? r2))
                (and (result/err? r1) (result/ok? r2)))
            "exactly one concurrent transition should succeed; the other must error")))))

;; ============================================================================
;; Query tests
;; ============================================================================

(deftest ready-tasks-test
  (testing "finds tasks with all deps satisfied"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-a (state/create-task-state task-a-id #{} :state-profile :kernel)
          task-b (state/create-task-state task-b-id #{task-a-id} :state-profile :kernel)
          run (state/create-run-state (random-uuid)
                                      {task-a-id task-a
                                       task-b-id task-b}
                                      :state-profile :kernel)]
      ;; Only task-a is ready (no deps)
      (is (= #{task-a-id} (state/ready-tasks run)))
      ;; After marking task-a completed, task-b becomes ready
      (let [run' (-> run
                     (assoc-in [:run/tasks task-a-id :task/status] :completed)
                     (state/mark-task-completed task-a-id))]
        (is (contains? (state/ready-tasks run') task-b-id))))))

(deftest blocked-tasks-test
  (testing "blocked tasks use profile success states instead of merge-only bookkeeping"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-a (state/create-task-state task-a-id #{} :state-profile :kernel)
          task-b (state/create-task-state task-b-id #{task-a-id} :state-profile :kernel)
          run (state/create-run-state (random-uuid)
                                      {task-a-id task-a
                                       task-b-id task-b}
                                      :state-profile :kernel)
          run' (-> run
                   (assoc-in [:run/tasks task-a-id :task/status] :completed)
                   (state/mark-task-completed task-a-id))]
      (is (= {task-b-id #{task-a-id}}
             (state/blocked-tasks run)))
      (is (= {}
             (state/blocked-tasks run'))))))

(deftest running-tasks-test
  (testing "running tasks respect the task profile rather than the kernel default"
    (let [provider (profiles/build-provider
                    {:default-profile :software-factory
                     :profiles
                     {:software-factory
                      {:profile/id :software-factory
                       :task-statuses [:pending :ready :implementing :merged :failed :skipped]
                       :terminal-statuses [:merged :failed :skipped]
                       :success-terminal-statuses [:merged]
                       :valid-transitions {:pending [:ready]
                                           :ready [:implementing]
                                           :implementing [:merged :failed]
                                           :merged []
                                           :failed []
                                           :skipped []}
                       :event-mappings {}}}})
          task-id (random-uuid)
          task (state/create-task-state task-id #{}
                                        :state-profile :software-factory
                                        :state-profile-provider provider)
          run (-> (state/create-run-state (random-uuid) {task-id task}
                                          :state-profile :software-factory
                                          :state-profile-provider provider)
                  (assoc-in [:run/tasks task-id :task/status] :merged))]
      (is (= #{} (state/running-tasks run))))))

(deftest terminal?-test
  (testing "terminal statuses"
    (is (state/terminal? :completed))
    (is (state/terminal? :failed))
    (is (state/terminal? :skipped))
    (is (not (state/terminal? :pending)))
    (is (not (state/terminal? :running))))

  (testing "terminal? respects explicit non-kernel profiles"
    (let [software-factory-provider
          (profiles/build-provider
           {:default-profile :software-factory
            :profiles
            {:software-factory
             {:profile/id :software-factory
              :task-statuses [:pending :ready :implementing :merged :failed :skipped]
              :terminal-statuses [:merged :failed :skipped]
              :success-terminal-statuses [:merged]
              :valid-transitions {:pending [:ready]
                                  :ready [:implementing]
                                  :implementing [:merged :failed]
                                  :merged []
                                  :failed []
                                  :skipped []}
              :event-mappings {}}}})]
      (is (state/terminal? (profiles/resolve-profile software-factory-provider :software-factory)
                           :merged)))))

(deftest resource-backed-profile-registry-test
  (testing "kernel profile registry is loaded from kernel resources"
    (is (= :kernel (profiles/default-profile-id)))
    (is (= [:kernel] (vec (profiles/available-profile-ids))))))

(deftest explicit-provider-resolution-test
  (testing "explicit providers resolve project-specific profiles without classpath overrides"
    (let [provider (profiles/build-provider
                    {:default-profile :software-factory
                     :profiles
                     {:software-factory
                      {:profile/id :software-factory
                       :task-statuses [:pending :ready :implementing :merged :failed :skipped]
                       :terminal-statuses [:merged :failed :skipped]
                       :success-terminal-statuses [:merged]
                       :valid-transitions {:pending [:ready]
                                           :ready [:implementing]
                                           :implementing [:merged :failed]
                                           :merged []
                                           :failed []
                                           :skipped []}
                       :event-mappings {:merged {:type :complete :to :merged}}}
                      :etl
                      {:profile/id :etl
                       :task-statuses [:pending :ready :running :completed :failed :skipped]
                       :terminal-statuses [:completed :failed :skipped]
                       :success-terminal-statuses [:completed]
                       :valid-transitions {:pending [:ready]
                                           :ready [:running]
                                           :running [:completed :failed]
                                           :completed []
                                           :failed []
                                           :skipped []}
                       :event-mappings {:completed {:type :complete :to :completed}}}}})]
      (is (= #{:software-factory :etl}
             (set (profiles/available-profile-ids provider))))
      (is (= :software-factory (profiles/default-profile-id provider)))
      (is (= :merged
             (-> (profiles/resolve-profile provider :software-factory)
                 :success-terminal-statuses
                 first))))))

(deftest profile-resolution-falls-back-to-kernel-test
  (testing "unknown profiles fall back to the configured default profile"
    (let [kernel-definition {:profile/id :kernel
                             :task-statuses [:pending :ready :running :completed :failed :skipped]
                             :terminal-statuses [:completed :failed :skipped]
                             :success-terminal-statuses [:completed]
                             :valid-transitions {:pending [:ready :skipped]
                                                 :ready [:running :skipped]
                                                 :running [:completed :failed]
                                                 :completed []
                                                 :failed []
                                                 :skipped []}
                             :event-mappings {:completed {:type :complete :to :completed}}}
          provider (profiles/build-provider
                    {:default-profile :kernel
                     :profiles {:kernel kernel-definition}})]
      (is (= :kernel (profiles/default-profile-id provider)))
      (is (= (profiles/build-profile kernel-definition)
             (profiles/resolve-profile provider :missing-profile))))))

(deftest build-profile-normalizes-transition-targets-test
  (testing "resource-shaped profile data is normalized into set-based transitions"
    (let [profile (profiles/build-profile
                   {:profile/id :vector-backed
                    :task-statuses [:pending :ready :completed]
                    :terminal-statuses [:completed]
                    :success-terminal-statuses [:completed]
                    :valid-transitions {:pending [:ready]
                                        :ready [:completed]
                                        :completed []}
                    :event-mappings {}})]
      (is (= #{:ready} (get-in profile [:valid-transitions :pending])))
      (is (state/valid-transition? profile :pending :ready))
      (is (not (state/valid-transition? profile :pending :completed))))))

(deftest resolve-provider-profile-test
  (testing "map-backed provider entries inherit the map key as profile id"
    (let [[profile-id profile]
          (profiles/resolve-provider-profile
           :software-factory
           {:task-statuses [:pending :ready :merged]
            :terminal-statuses [:merged]
            :success-terminal-statuses [:merged]
            :valid-transitions {:pending [:ready]
                                :ready [:merged]
                                :merged []}
            :event-mappings {:merged {:type :complete :to :merged}}})]
      (is (= :software-factory profile-id))
      (is (= :software-factory (:profile/id profile)))
      (is (= #{:ready} (get-in profile [:valid-transitions :pending])))))

  (testing "unsupported provider entries are ignored"
    (is (nil? (profiles/resolve-provider-profile :bogus 42)))))

(deftest kernel-profile-completion-test
  (testing "generic kernel profile reaches :completed without merge semantics"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{} :state-profile :kernel)
          run (state/create-run-state (random-uuid) {task-id task} :state-profile :kernel)
          run-atom (state/create-run-atom run)]
      (is (= (profiles/resolve-profile :kernel) (get-in @run-atom [:run/config :state-profile])))
      (is (result/ok? (state/transition-task! run-atom task-id :ready nil)))
      (is (result/ok? (state/transition-task! run-atom task-id :running nil)))
      (is (result/ok? (state/mark-completed! run-atom task-id nil)))
      (is (= :completed (get-in @run-atom [:run/tasks task-id :task/status])))
      (is (contains? (:run/completed @run-atom) task-id))
      (is (= :completed (state/compute-run-status @run-atom))))))

(deftest mark-failed!-test
  (testing "mark-failed! stores :task/error, sets status to :failed, and updates run failure set"
    (let [task-id    (random-uuid)
          task       (state/create-task-state task-id #{} :state-profile :kernel)
          run        (state/create-run-state (random-uuid) {task-id task} :state-profile :kernel)
          run-atom   (state/create-run-atom run)
          error-info {:reason :test-error}]
      (is (result/ok? (state/transition-task! run-atom task-id :ready nil)))
      (is (result/ok? (state/transition-task! run-atom task-id :running nil)))
      (let [fail-result    (state/mark-failed! run-atom task-id error-info nil)
            returned-state (:data fail-result)]
        (is (result/ok? fail-result))
        (is (= :failed (get-in returned-state [:run/tasks task-id :task/status])))
        (is (= error-info (get-in returned-state [:run/tasks task-id :task/error])))
        (is (contains? (:run/failed returned-state) task-id)))
      (is (= :failed (get-in @run-atom [:run/tasks task-id :task/status])))
      (is (= error-info (get-in @run-atom [:run/tasks task-id :task/error])))
      (is (contains? (:run/failed @run-atom) task-id)))))

(deftest explicit-provider-construction-test
  (testing "run construction uses the supplied provider for keyword profiles"
    (let [provider (profiles/build-provider
                    {:default-profile :software-factory
                     :profiles
                     {:software-factory
                      {:profile/id :software-factory
                       :task-statuses [:pending :ready :implementing :merged :failed :skipped]
                       :terminal-statuses [:merged :failed :skipped]
                       :success-terminal-statuses [:merged]
                       :valid-transitions {:pending [:ready]
                                           :ready [:implementing]
                                           :implementing [:merged :failed]
                                           :merged []
                                           :failed []
                                           :skipped []}
                       :event-mappings {:merged {:type :complete :to :merged}}}}})
          task-id (random-uuid)
          task (state/create-task-state task-id #{}
                                        :state-profile :software-factory
                                        :state-profile-provider provider)
          run (state/create-run-state (random-uuid) {task-id task}
                                      :state-profile :software-factory
                                      :state-profile-provider provider)
          run-atom (state/create-run-atom run)]
      (is (= :software-factory (get-in task [:task/state-profile :profile/id])))
      (is (= :software-factory (get-in run [:run/config :state-profile :profile/id])))
      (is (result/ok? (state/transition-task! run-atom task-id :ready nil)))
      (is (result/ok? (state/transition-task! run-atom task-id :implementing nil)))
      (is (result/ok? (state/mark-merged! run-atom task-id nil)))
      (is (= :merged (get-in @run-atom [:run/tasks task-id :task/status])))
      (is (= :completed (state/compute-run-status @run-atom))))))
