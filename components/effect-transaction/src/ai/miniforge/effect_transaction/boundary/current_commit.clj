;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.boundary.current-commit
  "Check runtime ports and pin issuance identity at the actual durable-record lookup."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.current-commit :as current]
            [ai.miniforge.effect-transaction.current-schema :as schema]
            [ai.miniforge.effect-transaction.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-current? (m/validator schema/CommitArguments))

(def ^{:stratum 0} ^:private valid-issued? (m/validator schema/IssuedCommitArguments))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/t :commit/invalid-input) {}))

(defn- ^{:stratum 0} lookup-issued [expected lookup requested]
  (if (= expected requested)
    (lookup requested)
    (anomaly/anomaly :unauthorized (msg/t :commit/issued-grant-mismatch) {})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} commit!
  ([dir id lookup clock operation]
   (if (valid-current? [dir id lookup clock operation])
     (current/commit! dir id lookup clock operation)
     (invalid)))
  ([dir id issued-id lookup clock operation]
   (if (valid-issued? [dir id issued-id lookup clock operation])
     (current/commit! dir id (partial lookup-issued issued-id lookup) clock operation)
     (invalid))))

(comment ::pin-at-current-read-not-a-separate-racy-proposal-precheck)
