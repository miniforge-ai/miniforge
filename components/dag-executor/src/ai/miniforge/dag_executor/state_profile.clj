(ns ai.miniforge.dag-executor.state-profile
  "State profiles for DAG task workflows.

   Profiles let the DAG executor support domain-neutral task lifecycles while
   preserving the existing software-factory semantics as the default."
  (:require
   [clojure.set :as set]))

(defn build-profile
  [{profile-id :profile/id
    :keys [task-statuses terminal-statuses success-terminal-statuses
           valid-transitions event-mappings]
    :as profile}]
  (let [terminal-statuses (set terminal-statuses)
        success-terminal-statuses (set success-terminal-statuses)]
    (assoc profile
           :profile/id profile-id
           :task-statuses (set task-statuses)
           :terminal-statuses terminal-statuses
           :success-terminal-statuses success-terminal-statuses
           :valid-transitions valid-transitions
           :event-mappings event-mappings
           :failure-terminal-statuses (set/difference terminal-statuses
                                                     success-terminal-statuses))))

(def software-factory-profile
  (build-profile
   {:profile/id :software-factory
    :task-statuses #{:pending :ready :implementing :pr-opening :ci-running
                     :review-pending :responding :ready-to-merge :merging
                     :merged :failed :skipped}
    :terminal-statuses #{:merged :failed :skipped}
    :success-terminal-statuses #{:merged}
    :valid-transitions
    {:pending #{:ready :skipped}
     :ready #{:implementing :skipped}
     :implementing #{:pr-opening :failed}
     :pr-opening #{:ci-running :failed}
     :ci-running #{:review-pending :responding :failed}
     :review-pending #{:ready-to-merge :responding}
     :responding #{:ci-running :failed}
     :ready-to-merge #{:merging :responding}
     :merging #{:merged :failed}
     :merged #{}
     :failed #{}
     :skipped #{}}
    :event-mappings
    {:pr-opened {:type :transition :to :ci-running}
     :ci-passed {:type :transition :to :review-pending}
     :ci-failed {:type :retry-or-fail
                 :retry-limit-predicate :max-ci-retries-exceeded?
                 :retry-update-fn :increment-ci-retries
                 :retry-transition :responding
                 :failure-reason :ci-failed-max-retries}
     :review-approved {:type :transition :to :ready-to-merge}
     :review-changes-requested {:type :retry-or-fail
                                :retry-limit-predicate :max-fix-iterations-exceeded?
                                :retry-update-fn :increment-fix-iterations
                                :retry-transition :responding
                                :failure-reason :review-failed-max-iterations}
     :fix-pushed {:type :transition :to :ci-running}
     :merge-ready {:type :transition :to :merging}
     :merged {:type :complete :to :merged}
     :merge-failed {:type :fail :reason :merge-failed}}}))

(def kernel-profile
  (build-profile
   {:profile/id :kernel
    :task-statuses #{:pending :ready :running :completed :failed :skipped}
    :terminal-statuses #{:completed :failed :skipped}
    :success-terminal-statuses #{:completed}
    :valid-transitions
    {:pending #{:ready :skipped}
     :ready #{:running :skipped}
     :running #{:completed :failed}
     :completed #{}
     :failed #{}
     :skipped #{}}
    :event-mappings
    {:started {:type :transition :to :running}
     :completed {:type :complete :to :completed}
     :failed {:type :fail :reason :failed}}}))

(def etl-profile
  (build-profile
   {:profile/id :etl
    :task-statuses #{:pending :ready :acquiring :extracting :canonicalizing
                     :evaluating :publishing :completed :failed :skipped}
    :terminal-statuses #{:completed :failed :skipped}
    :success-terminal-statuses #{:completed}
    :valid-transitions
    {:pending #{:ready :skipped}
     :ready #{:acquiring :skipped}
     :acquiring #{:extracting :failed}
     :extracting #{:canonicalizing :failed}
     :canonicalizing #{:evaluating :failed}
     :evaluating #{:publishing :failed}
     :publishing #{:completed :failed}
     :completed #{}
     :failed #{}
     :skipped #{}}
    :event-mappings
    {:acquired {:type :transition :to :extracting}
     :extracted {:type :transition :to :canonicalizing}
     :canonicalized {:type :transition :to :evaluating}
     :evaluated {:type :transition :to :publishing}
     :published {:type :complete :to :completed}
     :failed {:type :fail :reason :failed}}}))

(def known-profiles
  {:software-factory software-factory-profile
   :kernel kernel-profile
   :etl etl-profile})

(def default-profile software-factory-profile)

(defn resolve-profile
  [profile]
  (cond
    (nil? profile) default-profile
    (keyword? profile) (get known-profiles profile default-profile)
    (map? profile) (build-profile profile)
    :else default-profile))
