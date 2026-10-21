;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-notifications-test
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-environment :as environment]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture! [sink entry] (swap! sink conj entry))

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
