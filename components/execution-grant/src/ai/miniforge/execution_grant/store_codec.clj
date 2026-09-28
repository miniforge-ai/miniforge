;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-codec
  "Preserve grant timestamps across the EDN storage boundary."
  (:require [ai.miniforge.execution-grant.temporal :as temporal])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} instant-keys
  [:grant/issued-at :grant/expires-at :grant/revoked-at])

(defn- ^{:stratum 0} encode-instant
  [record k]
  (if-some [value (get record k)]
    (assoc record k (str (temporal/->instant value)))
    record))

(defn- ^{:stratum 0} decode-instant
  [record k]
  (if-some [value (get record k)]
    (assoc record k (Instant/parse value))
    record))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ->wire [record]
  (reduce encode-instant record instant-keys))

(defn ^{:stratum 1} <-wire [record]
  (reduce decode-instant record instant-keys))

(comment
  (<-wire (->wire {:grant/revoked-at nil})))
