;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-location-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-location :as location]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} announcement [id]
  (let [root (str (fs/absolutize "checkpoint-root"))
        directory (str (fs/absolutize "physical-run"))
        locator (events/checkpoint-location root directory)
        stream (events/create-event-stream {:sinks []})
        path (str (fs/path directory "generations" "generation" "machine.edn"))]
    (events/machine-snapshot-written stream id :running path 1 locator)))

(defn- ^{:stratum 0} without-location [event]
  (-> event (dissoc :checkpoint/location) (assoc :event/version "1.0.0")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} physical-identity-survives-compatible-minor-versions
  (let [id (random-uuid)
        event (announcement id)
        expected (:checkpoint/location event)]
    (is (= expected (location/from-event id event)))
    (is (= expected (location/from-event id (assoc event :event/version "1.2.0")))))
  (is (nil? (location/from-event (random-uuid) {:event/type :workflow/resumed}))))

(deftest ^{:stratum 1} legacy-identity-does-not-guess-from-generations-or-relative-paths
  (let [id (random-uuid)
        event (announcement id)
        root (get-in event [:checkpoint/location :checkpoint/root])
        directory (str (fs/path root (str id)))
        legacy (without-location event)
        flat (assoc legacy :snapshot/path (str (fs/path directory "machine.edn")))
        expected (events/checkpoint-location root directory)]
    (is (= expected (location/from-event id (assoc legacy :checkpoint/root root))))
    (is (= expected (location/from-event id flat)))
    (doseq [fact [legacy
                  (assoc flat :snapshot/path (str (fs/path "relative-root" (str id) "machine.edn")))
                  (assoc legacy :checkpoint/root "relative-root")
                  (assoc event :event/version "1.0.0")
                  (assoc event :event/version "2.0.0")
                  (assoc event :event/version nil)
                  (assoc event :workflow/id (random-uuid))]]
      (is (= :unknown (location/from-event id fact))))))

(comment ::checkpoint-location-profiles)
