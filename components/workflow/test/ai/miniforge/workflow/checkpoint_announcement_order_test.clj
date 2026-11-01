;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-announcement-order-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-announcements :as announcements]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private legacy-name "20260101T000000000Z-old.json")

(def ^{:stratum 0} ^:private later-snowflake-name
  "The Snowflake timestamp is epoch + 1000ms; lexical order puts it before legacy-name."
  "00000000fa000000__000000000001.transit.json")

(def ^{:stratum 0} ^:private tied-legacy-name "20260101T000001000Z-tie.json")

(defn- ^{:stratum 0} archive! [directory filename event]
  (spit (fs/file directory filename) (events/serialize-event event)))

(defn- ^{:stratum 0} announcement [id locator]
  (let [stream (events/create-event-stream {:sinks []})
        path (str (fs/path (:checkpoint/run-directory locator) "machine.edn"))]
    (events/machine-snapshot-written stream id :running path 1 locator)))

(defn- ^{:stratum 0} locator [root name id]
  (let [directory (str (fs/path root name))]
    (events/checkpoint-location directory (str (fs/path directory (str id))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-ordering [kind root]
  (let [id (random-uuid)
        events-dir (fs/path root "events")
        directory (fs/create-dirs (fs/path events-dir "archived" (str id)))
        old (locator root "deleted" id)
        current (locator root "current" id)
        run (fs/create-dirs (:checkpoint/run-directory current))]
    (spit (fs/file run "machine.edn") "{}")
    (archive! directory legacy-name (announcement id old))
    (archive! directory later-snowflake-name (announcement id current))
    (case kind
      :unreadable (spit (fs/file directory later-snowflake-name) "not transit")
      :unknown (archive! directory "unrecognized.json" (announcement id old))
      :malformed-time (archive! directory "20269999T999999Z-invalid.json" (announcement id old))
      :conflicting-tie (archive! directory tied-legacy-name (announcement id old))
      :same-location-tie (archive! directory tied-legacy-name (announcement id current))
      :mixed nil)
    (is (true? (events/retention-hold ::gc/checkpoint (str id) events-dir)))
    (if (#{:mixed :same-location-tie} kind)
      (is (= current (announcements/latest events-dir id)))
      (is (anomaly/anomaly? (gc/run-checkpoint-root id events-dir))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} mixed-filename-grammars-use-recorded-time-not-lexical-order
  (is (< (events/event-file-ms legacy-name) (events/event-file-ms later-snowflake-name)))
  (fixture/call-with-temp-checkpoint-root (partial verify-ordering :mixed)))

(deftest ^{:stratum 2} a-newer-unreadable-snowflake-file-cannot-be-bypassed
  (fixture/call-with-temp-checkpoint-root (partial verify-ordering :unreadable)))

(deftest ^{:stratum 2} unknown-or-malformed-filename-times-preserve-uncertainty
  (doseq [kind [:unknown :malformed-time]]
    (fixture/call-with-temp-checkpoint-root (partial verify-ordering kind))))

(deftest ^{:stratum 2} ties-cannot-choose-between-distinct-physical-locations
  (fixture/call-with-temp-checkpoint-root (partial verify-ordering :conflicting-tie)))

(deftest ^{:stratum 2} agreeing-locators-in-a-timestamp-tie-do-not-create-false-uncertainty
  (fixture/call-with-temp-checkpoint-root (partial verify-ordering :same-location-tie)))

(comment ::mixed-retained-filename-order)
