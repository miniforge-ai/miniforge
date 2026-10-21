;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-notifications-test
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-environment :as environment]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture! [sink entry] (swap! sink conj entry))

(defn- ^{:stratum 0} fail! [failure & _] (throw failure))

(defn- ^{:stratum 0} verify-interruption! [failure operation]
  (try+
    (let [caught (try+ (operation) (catch Object _ (:throwable &throw-context)))
          flagged? (.isInterrupted (Thread/currentThread))]
      (is (identical? failure caught))
      (is flagged?))
    (finally (Thread/interrupted))))

(defn- ^{:stratum 0} context [mode]
  {:execution/mode mode
   :execution/id (random-uuid)
   :execution/executor :fixture
   :execution/environment-id (random-uuid)
   :execution/worktree-path "/fixture/worktree"})

(defn- ^{:stratum 0} persisted [reference]
  (dag/ok {:persisted? true :bundle-path reference :branch "fixture-branch"}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} capturing-logger [sink]
  (log/create-logger {:min-level :debug :output (partial capture! sink)}))

(deftest ^{:stratum 1} public-persistence-preserves-publication-interruption
  (let [failure (InterruptedException. "fixture publication interruption")]
    (with-redefs [dag/persist-workspace! (constantly (persisted "fixture.bundle"))
                  events/workspace-persisted (constantly {})
                  events/publish! (partial fail! failure)]
      (verify-interruption! failure
                            (partial environment/persist-workspace-at-phase-boundary!
                                     (assoc (context :local) :event-stream :fixture)
                                     {:execution/current-phase :plan})))))

(deftest ^{:stratum 1} local-acquisition-cannot-swallow-interruption
  (let [failure (InterruptedException. "fixture acquisition interruption")]
    (with-redefs [environment/dag-executor-fns (partial fail! failure)]
      (verify-interruption! failure
                            (partial environment/acquire-execution-environment! (random-uuid) {})))))

(deftest ^{:stratum 1} local-restoration-cannot-swallow-interruption
  (let [failure (InterruptedException. "fixture restore interruption")
        options {:resume-workspace {:bundle-path "fixture.bundle" :branch "fixture"}}]
    (with-redefs [dag/create-executor-registry (constantly :registry)
                  dag/select-executor (constantly :executor)
                  dag/restore-workspace! (partial fail! failure)
                  dag/acquire-environment! (constantly nil)]
      (verify-interruption! failure
                            (partial environment/acquire-execution-environment! (random-uuid) options)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} public-persistence-renders-catalog-text-and-retains-tier
  (doseq [[mode tier] [[:local :worktree] [:governed :remote] [nil :worktree]]]
    (let [sink (atom []) reference "fixture.bundle"
          logger (capturing-logger sink)]
      (with-redefs [environment/env-logger logger
                    dag/persist-workspace! (constantly (persisted reference))]
        (environment/persist-workspace-at-phase-boundary! (context mode) {:execution/current-phase :plan}))
      (let [entry (first @sink)
            message (:log/message entry)]
        (is (= :workflow/workspace-persisted (:log/event entry)))
        (is (= tier (get-in entry [:data :persist-tier])))
        (is (= (messages/system :env/persisted {:reference reference}) message))
        (is (str/includes? message reference))))))

(comment ::packaged-persistence-diagnostics)
