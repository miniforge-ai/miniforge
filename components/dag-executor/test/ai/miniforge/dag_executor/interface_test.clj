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

(ns ai.miniforge.dag-executor.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.interface :as sut]
   [ai.miniforge.dag-executor.executor :as executor]
   [ai.miniforge.dag-executor.parallel :as parallel]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scheduler :as scheduler]
   [ai.miniforge.dag-executor.state :as state]
   [ai.miniforge.dag-executor.state-profile :as state-profile]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- task-def
  [id deps]
  {:task/id id :task/deps deps})

(defrecord ^:private MockExecutor [exec-type available?-result calls]
  proto/TaskExecutor
  (executor-type        [_]                  exec-type)
  (available?           [_]                  available?-result)
  (acquire-environment! [_ task-id config]
    (swap! calls conj [:acquire task-id config])
    (result/ok {:environment-id (str "env-" task-id)
                :type :container
                :workdir "/work"}))
  (execute!             [_ env-id command opts]
    (swap! calls conj [:execute env-id command opts])
    (result/ok {:exit-code 0 :stdout "" :stderr ""}))
  (copy-to!             [_ _ _ _]            (result/ok {:copied-bytes 0}))
  (copy-from!           [_ _ _ _]            (result/ok {:copied-bytes 0}))
  (release-environment! [_ env-id]
    (swap! calls conj [:release env-id])
    (result/ok {:released? true}))
  (environment-status   [_ _]                (result/ok {:status :running}))
  (persist-workspace!   [_ _ _]              (result/ok {:persisted? true}))
  (restore-workspace!   [_ _ _]              (result/ok {:restored? true})))

(defn- mock-executor
  "Build a MockExecutor with `exec-type` and a configurable available?-result."
  [exec-type available?-data]
  (->MockExecutor exec-type
                  (result/ok available?-data)
                  (atom [])))

;------------------------------------------------------------------------------ Layer 1
;; Re-export verification — defs in interface point at the underlying impl vars

(deftest result-helpers-re-exported-test
  (testing "result/* helpers are re-exported"
    (is (= result/ok        sut/ok))
    (is (= result/err       sut/err))
    (is (= result/ok?       sut/ok?))
    (is (= result/err?      sut/err?))
    (is (= result/unwrap    sut/unwrap))
    (is (= result/unwrap-or sut/unwrap-or))))

(deftest state-helpers-re-exported-test
  (testing "state/* helpers are re-exported"
    (is (= state/task-statuses     sut/task-statuses))
    (is (= state/terminal-statuses sut/terminal-statuses))
    (is (= state/run-statuses      sut/run-statuses))
    (is (= state/create-run-atom   sut/create-run-atom))
    (is (= state/terminal?         sut/terminal?))
    (is (= state/valid-transition? sut/valid-transition?))
    (is (= state/transition-task!  sut/transition-task!))
    (is (= state/mark-merged!      sut/mark-merged!))
    (is (= state/mark-completed!   sut/mark-completed!))
    (is (= state/mark-failed!      sut/mark-failed!))
    (is (= state/update-task!      sut/update-task!))
    (is (= state/update-metrics!   sut/update-metrics!))
    (is (= state/ready-tasks       sut/ready-tasks))
    (is (= state/running-tasks     sut/running-tasks))
    (is (= state/blocked-tasks     sut/blocked-tasks))
    (is (= state/all-terminal?     sut/all-terminal?))
    (is (= state/compute-run-status sut/compute-run-status))))

(deftest state-profile-helpers-re-exported-test
  (testing "state-profile/* helpers are re-exported"
    (is (= state-profile/build-profile  sut/build-state-profile))
    (is (= state-profile/build-provider sut/build-state-profile-provider))))

(deftest parallel-helpers-re-exported-test
  (testing "parallel/* helpers are re-exported"
    (is (= parallel/create-lock-pool       sut/create-lock-pool))
    (is (= parallel/acquire-repo-write!    sut/acquire-repo-write!))
    (is (= parallel/release-repo-write!    sut/release-repo-write!))
    (is (= parallel/acquire-file-locks!    sut/acquire-file-locks!))
    (is (= parallel/release-file-locks!    sut/release-file-locks!))
    (is (= parallel/acquire-worktree!      sut/acquire-worktree!))
    (is (= parallel/release-worktree!      sut/release-worktree!))
    (is (= parallel/release-all-locks!     sut/release-all-locks!))
    (is (= parallel/available-capacity     sut/available-capacity))
    (is (= parallel/select-parallel-batch  sut/select-parallel-batch))))

(deftest scheduler-helpers-re-exported-test
  (testing "scheduler/* helpers are re-exported"
    (is (= scheduler/create-scheduler-context sut/create-scheduler-context))
    (is (= scheduler/handle-task-event        sut/handle-task-event))
    (is (= scheduler/run-scheduler            sut/run-scheduler))
    (is (= scheduler/pause-scheduler          sut/pause-scheduler))
    (is (= scheduler/resume-scheduler         sut/resume-scheduler))
    (is (= scheduler/schedule-iteration       sut/schedule-iteration))))

(deftest executor-helpers-re-exported-test
  (testing "executor/* helpers are re-exported"
    (is (= executor/TaskExecutor              sut/TaskExecutor))
    (is (= executor/create-executor-registry  sut/create-executor-registry))
    (is (= executor/select-executor           sut/select-executor))
    (is (= executor/create-kubernetes-executor sut/create-kubernetes-executor))
    (is (= executor/create-docker-executor    sut/create-docker-executor))
    (is (= executor/create-worktree-executor  sut/create-worktree-executor))
    (is (= executor/execute!                  sut/execute!))
    (is (= executor/persist-workspace!        sut/persist-workspace!))
    (is (= executor/restore-workspace!        sut/restore-workspace!))
    (is (= executor/with-environment          sut/with-environment))
    (is (= executor/clone-and-checkout!       sut/clone-and-checkout!))
    (is (= executor/prepare-docker-executor!  sut/prepare-docker-executor!))
    (is (= executor/ensure-image!             sut/ensure-image!))
    (is (= executor/task-runner-images        sut/task-runner-images))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-state-profile and available-state-profile-ids — arity dispatch

(deftest resolve-state-profile-default-arity-test
  (testing "single-arity resolves against the kernel provider"
    (let [resolved (sut/resolve-state-profile :kernel)]
      (is (= :kernel (:profile/id resolved))))))

(deftest resolve-state-profile-custom-provider-arity-test
  (testing "two-arity uses the supplied provider"
    (let [provider {:default-profile :alt
                    :profiles {:alt {:profile/id        :alt
                                     :task-statuses     [:pending :ready :done]
                                     :terminal-statuses [:done]
                                     :success-terminal-statuses [:done]
                                     :valid-transitions {:pending [:ready]
                                                         :ready   [:done]
                                                         :done    []}
                                     :event-mappings    {}}}}
          resolved (sut/resolve-state-profile provider :alt)]
      (is (= :alt (:profile/id resolved))))))

(deftest available-state-profile-ids-default-arity-test
  (testing "single-arity reads from the kernel provider"
    (is (some #{:kernel} (sut/available-state-profile-ids)))))

(deftest available-state-profile-ids-custom-provider-arity-test
  (testing "two-arity reads from the supplied provider"
    (let [provider {:default-profile :one
                    :profiles {:one {:profile/id :one
                                     :task-statuses [:pending :done]
                                     :terminal-statuses [:done]
                                     :success-terminal-statuses [:done]
                                     :valid-transitions {:pending [:done] :done []}
                                     :event-mappings {}}
                              :two {:profile/id :two
                                    :task-statuses [:pending :done]
                                    :terminal-statuses [:done]
                                    :success-terminal-statuses [:done]
                                    :valid-transitions {:pending [:done] :done []}
                                    :event-mappings {}}}}]
      (is (= #{:one :two} (set (sut/available-state-profile-ids provider)))))))

;------------------------------------------------------------------------------ Layer 1
;; create-task-state passthrough — smoke + option propagation

(deftest create-task-state-defaults-test
  (testing "create-task-state returns a pending task with default config"
    (let [tid (random-uuid)
          t   (sut/create-task-state tid #{})]
      (is (= tid (:task/id t)))
      (is (= :pending (:task/status t)))
      (is (= #{} (:task/deps t)))
      (is (= 5 (get-in t [:task/config :max-fix-iterations])))
      (is (= 3 (get-in t [:task/config :max-ci-retries])))
      (is (inst? (:task/created-at t)))
      (is (inst? (:task/updated-at t))))))

(deftest create-task-state-options-propagate-test
  (testing "max-fix-iterations and max-ci-retries options pass through"
    (let [t (sut/create-task-state (random-uuid) #{}
                                   :max-fix-iterations 9
                                   :max-ci-retries 7)]
      (is (= 9 (get-in t [:task/config :max-fix-iterations])))
      (is (= 7 (get-in t [:task/config :max-ci-retries]))))))

(deftest create-task-state-coerces-deps-to-set-test
  (testing "Deps are normalized to a set"
    (let [a (random-uuid) b (random-uuid)
          t (sut/create-task-state (random-uuid) [a b a])]
      (is (= #{a b} (:task/deps t))))))

;------------------------------------------------------------------------------ Layer 1
;; create-run-state passthrough — smoke + budget propagation

(deftest create-run-state-defaults-test
  (testing "create-run-state returns a pending run with empty tracker sets"
    (let [dag-id (random-uuid)
          tid    (random-uuid)
          tasks  {tid (sut/create-task-state tid #{})}
          rs     (sut/create-run-state dag-id tasks)]
      (is (= dag-id (:dag/id rs)))
      (is (uuid? (:run/id rs)))
      (is (= :pending (:run/status rs)))
      (is (contains? (:run/tasks rs) tid))
      (is (= #{} (:run/completed rs)))
      (is (= #{} (:run/merged rs)))
      (is (= #{} (:run/failed rs))))))

(deftest create-run-state-budget-propagates-test
  (testing "Supplied :budget option is preserved under :run/config"
    (let [budget {:max-tokens 1000000 :max-cost-usd 50.0 :max-duration-ms 600000}
          rs     (sut/create-run-state (random-uuid) {} :budget budget)]
      (is (= budget (get-in rs [:run/config :budget]))))))

;------------------------------------------------------------------------------ Layer 1
;; create-dag-from-tasks — the meaningful logic in interface

(deftest create-dag-from-tasks-empty-test
  (testing "An empty task seq produces a run with no tasks"
    (let [rs (sut/create-dag-from-tasks (random-uuid) [])]
      (is (= {} (:run/tasks rs)))
      (is (= :pending (:run/status rs))))))

(deftest create-dag-from-tasks-single-task-test
  (testing "A single task with no deps becomes a pending task in the run"
    (let [tid (random-uuid)
          rs  (sut/create-dag-from-tasks (random-uuid) [(task-def tid #{})])]
      (is (= 1 (count (:run/tasks rs))))
      (is (= :pending (get-in rs [:run/tasks tid :task/status])))
      (is (= #{} (get-in rs [:run/tasks tid :task/deps]))))))

(deftest create-dag-from-tasks-deps-preserved-test
  (testing "Each task's :task/deps survives into the run-state"
    (let [a (random-uuid) b (random-uuid) c (random-uuid)
          rs (sut/create-dag-from-tasks
              (random-uuid)
              [(task-def a #{})
               (task-def b #{a})
               (task-def c #{a b})])]
      (is (= #{}    (get-in rs [:run/tasks a :task/deps])))
      (is (= #{a}   (get-in rs [:run/tasks b :task/deps])))
      (is (= #{a b} (get-in rs [:run/tasks c :task/deps]))))))

(deftest create-dag-from-tasks-budget-propagates-test
  (testing "The :budget option flows through into :run/config :budget"
    (let [budget {:max-tokens 500000 :max-cost-usd 25.0}
          rs (sut/create-dag-from-tasks (random-uuid) [] :budget budget)]
      (is (= budget (get-in rs [:run/config :budget]))))))

(deftest create-dag-from-tasks-iteration-overrides-propagate-test
  (testing ":max-fix-iterations and :max-ci-retries options reach each task"
    (let [a (random-uuid) b (random-uuid)
          rs (sut/create-dag-from-tasks
              (random-uuid)
              [(task-def a #{}) (task-def b #{a})]
              :max-fix-iterations 11
              :max-ci-retries 2)]
      (doseq [tid [a b]]
        (is (= 11 (get-in rs [:run/tasks tid :task/config :max-fix-iterations])))
        (is (= 2  (get-in rs [:run/tasks tid :task/config :max-ci-retries])))))))

(deftest create-dag-from-tasks-missing-deps-becomes-empty-set-test
  (testing "A task-def without :task/deps still gets an empty set"
    (let [tid (random-uuid)
          rs (sut/create-dag-from-tasks (random-uuid) [{:task/id tid}])]
      (is (= #{} (get-in rs [:run/tasks tid :task/deps]))))))

(deftest create-dag-from-tasks-ready-tasks-discoverable-test
  (testing "Tasks with no deps are immediately discoverable as :ready by ready-tasks"
    (let [a (random-uuid) b (random-uuid)
          rs (sut/create-dag-from-tasks (random-uuid)
                                        [(task-def a #{})
                                         (task-def b #{a})])]
      (is (= #{a} (sut/ready-tasks rs))))))

;------------------------------------------------------------------------------ Layer 2
;; Mock-backed executor protocol passthroughs

(deftest executor-type-passthrough-test
  (testing "executor-type returns the implementation's type keyword"
    (is (= :docker  (sut/executor-type (mock-executor :docker  {:available? true}))))
    (is (= :worktree (sut/executor-type (mock-executor :worktree {:available? true}))))))

(deftest executor-available?-passthrough-test
  (testing "executor-available? routes through the protocol"
    (let [exec (mock-executor :docker {:available? true})
          ret  (sut/executor-available? exec)]
      (is (result/ok? ret))
      (is (true? (:available? (:data ret)))))))

(deftest acquire-and-release-environment-passthroughs-test
  (testing "acquire-environment! / release-environment! reach the protocol impl"
    (let [exec (mock-executor :docker {:available? true})
          tid  (random-uuid)
          ret  (sut/acquire-environment! exec tid {:repo-url "git@x:y" :branch "main"})]
      (is (result/ok? ret))
      (is (= (str "env-" tid) (-> ret :data :environment-id)))
      (is (some #(= [:acquire tid {:repo-url "git@x:y" :branch "main"}] %)
                @(:calls exec)))
      (let [release (sut/release-environment! exec (-> ret :data :environment-id))]
        (is (result/ok? release))
        (is (some #(and (vector? %) (= :release (first %))) @(:calls exec)))))))

(deftest executor-execute-passthrough-test
  (testing "executor-execute! delegates to the protocol with command + opts"
    (let [exec (mock-executor :docker {:available? true})
          ret  (sut/executor-execute! exec "env-1" "make test"
                                      {:timeout-ms 5000 :capture-output? true})]
      (is (result/ok? ret))
      (is (= 0 (-> ret :data :exit-code)))
      (is (some #(and (vector? %)
                      (= :execute (first %))
                      (= "make test" (nth % 2)))
                @(:calls exec))))))

;------------------------------------------------------------------------------ Layer 2
;; select-executor — preference, availability filtering

(deftest select-executor-picks-priority-order-test
  (testing "Default order prefers kubernetes, then docker, then worktree"
    (let [k (mock-executor :kubernetes {:available? true})
          d (mock-executor :docker     {:available? true})
          w (mock-executor :worktree   {:available? true})]
      (is (= :kubernetes (sut/executor-type
                          (sut/select-executor {:kubernetes k :docker d :worktree w}))))
      (is (= :docker     (sut/executor-type
                          (sut/select-executor {:docker d :worktree w}))))
      (is (= :worktree   (sut/executor-type
                          (sut/select-executor {:worktree w})))))))

(deftest select-executor-skips-unavailable-test
  (testing "An executor reporting :available? false is skipped over"
    (let [k (mock-executor :kubernetes {:available? false :reason "no kubeconfig"})
          d (mock-executor :docker     {:available? true})
          w (mock-executor :worktree   {:available? true})]
      (is (= :docker (sut/executor-type
                      (sut/select-executor {:kubernetes k :docker d :worktree w})))))))

(deftest select-executor-honors-preferred-override-test
  (testing ":preferred is tried first regardless of priority order"
    (let [k (mock-executor :kubernetes {:available? true})
          d (mock-executor :docker     {:available? true})
          w (mock-executor :worktree   {:available? true})]
      (is (= :worktree (sut/executor-type
                        (sut/select-executor {:kubernetes k :docker d :worktree w}
                                             :preferred :worktree)))))))

(deftest select-executor-nil-when-none-available-test
  (testing "Returns nil when no executors are available"
    (is (nil? (sut/select-executor {})))
    (let [k (mock-executor :kubernetes {:available? false :reason "x"})
          d (mock-executor :docker     {:available? false :reason "y"})]
      (is (nil? (sut/select-executor {:kubernetes k :docker d}))))))
