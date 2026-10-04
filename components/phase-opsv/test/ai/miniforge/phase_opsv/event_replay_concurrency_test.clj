;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-replay-concurrency-test
  (:require [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.phase-opsv.event-delivery :as delivery]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} await-blocked [thread]
  (loop [remaining 200]
    (cond
      (nil? thread) false
      (= "BLOCKED" (str (.getState ^Thread thread))) true
      (zero? remaining) false
      :else (do (Thread/sleep 5) (recur (dec remaining))))))

(defn- ^{:stratum 0} paused-publication [publish entered release event-stream event]
  (deliver entered true)
  (deref release 5000 :timeout)
  (publish event-stream event))

(defn- ^{:stratum 0} retry-event [event-stream]
  (stream/actuation-emitted event-stream (random-uuid) (random-uuid)
    {:opsv/requested-actuation-mode :recommend-only :opsv/effective-actuation-mode :recommend-only
     :opsv/governed-effects [] :opsv/pr-refs [] :opsv/apply-refs []}))

(defn- ^{:stratum 0} competing-publication [thread event-stream event]
  (deliver thread (Thread/currentThread))
  (delivery/emit! {} event-stream event))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-competing-retry [event-stream event entered release]
  (let [first-call (future (delivery/emit! {} event-stream event))]
    (try
      (is (= true (deref entered 2000 :timeout)))
      (let [thread (promise)
            second-call (future (competing-publication thread event-stream event))]
        (try
          (is (await-blocked (deref thread 2000 nil)))
          (deliver release true)
          (is (nil? (deref first-call 2000 :timeout)))
          (is (nil? (deref second-call 2000 :timeout)))
          (is (= 1 (count (stream/get-events event-stream))))
          (finally (deliver release true) (future-cancel second-call))))
      (finally (deliver release true) (future-cancel first-call)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} concurrent-retries-publish-one-occurrence-test
  (let [event-stream (stream/create-event-stream {:sinks []})
        event (retry-event event-stream)
        entered (promise) release (promise)
        publish (partial paused-publication stream/publish! entered release)]
    (with-redefs [stream/publish! publish]
      (assert-competing-retry event-stream event entered release))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.event-replay-concurrency-test))
