;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-authority-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.java.io :as io]
            [clojure.test :refer [is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now
  "Fixed audit time makes durable replay comparisons deterministic."
  (Instant/parse "2026-11-15T00:00:00Z"))

(def ^{:stratum 0} reason
  "A nonempty operator reason binds the stop request to its audit record."
  "operator requested full stop")

(def ^{:stratum 0} wait-ms
  "Bound concurrent admission/stop regressions; a deadlock is not a successful fence."
  5000)

(def ^{:stratum 0} grant-lifetime-seconds
  "A bounded one-minute grant verifies legacy storage without expiry races."
  60)

(def ^{:stratum 0} principal
  "A runtime-owned principal for the backward-compatible grant lookup regression."
  "workflow:run-authority-test")

(defn ^{:stratum 0} state-file [root id]
  (io/file root "runs" (str id) "authority.edn"))

(defn ^{:stratum 0} io-failure [& _]
  (anomaly/anomaly :fault (msg/t :run/write-failed) {}))

(defn ^{:stratum 0} call-with-root [verify!]
  (let [root (.getCanonicalFile (.toFile (Files/createTempDirectory "run-authority" (into-array FileAttribute []))))]
    (try
      (when (= :posix (durability/filesystem-profile root)) (verify! root))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete ^java.io.File file))))))

(defn ^{:stratum 0} stop-before-registration! [root request write! directory record replace?]
  (when (= :active (:authority/state record))
    (is (anomaly/anomaly? (grant/full-stop-run-authority! root request))))
  (write! directory record replace?))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-then-fail [publish! & args]
  (apply publish! args)
  (io-failure))

(defn ^{:stratum 1} issued-grant []
  (let [expires-at (.plusSeconds now grant-lifetime-seconds)]
    (grant/issue {:principal principal
                 :effect-class :effect/pr-create
                 :scope {:effect/id (random-uuid)}
                 :constraints {:constraint/max-count 1}
                 :delegable? false
                 :expires-at expires-at} now)))

(defn ^{:stratum 1} stop-request [id]
  {:workflow-run/id id
   :intervention/id (random-uuid)
   :authority/stopped-at now
   :authority/reason reason})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} invalid-stop-requests []
  (let [request (stop-request (random-uuid))]
    [(dissoc request :workflow-run/id)
     (dissoc request :intervention/id)
     (assoc request :workflow-run/id (str (:workflow-run/id request)))
     (assoc request :authority/stopped-at nil)
     (assoc request :authority/reason "")
     (assoc request :authority/state :active)]))
