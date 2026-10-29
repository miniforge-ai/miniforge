;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-runtime-fixtures
  (:require [ai.miniforge.event-stream.boundary.heartbeat-scheduler :as scheduler]
            [ai.miniforge.event-stream.heartbeat-clock :as clock]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} clock-values [monotonic wall]
  {:monotonic-ms monotonic :wall-time (java.util.Date. wall)})

(defn- ^{:stratum 0} capture-task [tasks _phase _interval task] (swap! tasks conj task) {})

(defn- ^{:stratum 0} read-clock [state key] (get @state key))

(defn ^{:stratum 0} tick! [tasks] ((last @tasks)))

(defn ^{:stratum 0} start [stream workflow-id sources]
  (events/start-heartbeat! stream workflow-id :opsv/execute
                           {:interval-ms 10 :progress-sources sources}))

(defn ^{:stratum 0} verify-retained [expected stream _]
  (is (= expected (events/get-events stream))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} advance! [state monotonic wall] (reset! state (clock-values monotonic wall)))

(defn- ^{:stratum 1} with-clock [operation stream options]
  (let [state (atom (clock-values 0 100))
        tasks (atom [])]
    (with-redefs [clock/wall-time (partial read-clock state :wall-time)
                  clock/monotonic-ms (partial read-clock state :monotonic-ms)
                  scheduler/start! (partial capture-task tasks)]
      (operation stream options state tasks))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-runtime [operation]
  (journal/with-stream (partial with-clock operation)))

(comment ::deterministic-current-heartbeats)
