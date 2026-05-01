;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.engine-test
  (:require
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.reliability.interface :as rel]
   [clojure.test :refer [deftest is]]))

(defn- dependency-failure
  [overrides]
  (merge {:failure/class :failure.class/external
          :failure/source :external-provider
          :failure/vendor :anthropic
          :dependency/class :rate-limit
          :dependency/retryability :retryable
          :failure/message "rate limit"}
         overrides))

(deftest compute-cycle-emits-dependency-health-updated-events
  (let [event-stream (stream/create-event-stream {:sinks []})
        engine (rel/create-engine event-stream {:tiers []})
        result (rel/compute-cycle! engine {:dependency/incidents [(dependency-failure {})]})
        events (:events @event-stream)
        dependency-events (filter #(= :dependency/health-updated (:event/type %)) events)
        dependency-event (first dependency-events)]
    (is (= :degraded (:recommendation result)))
    (is (= :degraded (get-in result [:dependency-health :anthropic :dependency/status])))
    (is (= 1 (count dependency-events)))
    (is (= :anthropic (:dependency/id dependency-event)))
    (is (= :degraded (:dependency/status dependency-event)))))

(deftest compute-cycle-emits-dependency-recovered-events
  (let [event-stream (stream/create-event-stream {:sinks []})
        engine (rel/create-engine event-stream {:tiers []})]
    (rel/compute-cycle! engine {:dependency/incidents (repeat 3 (dependency-failure {:dependency/class :outage}))})
    (rel/compute-cycle! engine {:dependency/recoveries [{:dependency/id :anthropic
                                                         :failure/source :external-provider
                                                         :failure/vendor :anthropic}]})
    (let [events (:events @event-stream)
          recovered-events (filter #(= :dependency/recovered (:event/type %)) events)
          recovered-event (last recovered-events)]
      (is (= 1 (count recovered-events)))
      (is (= :healthy (:dependency/status recovered-event)))
      (is (= :unavailable (:dependency/previous-status recovered-event)))))) 
