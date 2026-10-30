;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.attempt
  "Deterministic effect ids for repeated attempts at one effect.

   The id of attempt n is a name-based UUID of the effect's basis (class,
   run, exact target) and n. A resumed or retried run computes the same
   ids, finds the records earlier attempts left, and takes the next free
   one; two processes racing for one attempt collide on its record."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.store :as store])
  (:import
   [java.nio.charset StandardCharsets]
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} max-attempts
  "Recorded attempts at one effect basis before refusing to start another."
  64)

(defn ^{:stratum 0} attempt-id
  "The effect id of attempt `n` at the effect `basis` names; the same in
   every process. `basis` is plain data: keywords, strings, numbers, UUIDs."
  [basis n]
  (UUID/nameUUIDFromBytes
   (.getBytes ^String (binding [*print-length* nil *print-level* nil]
                        (pr-str [basis n]))
              StandardCharsets/UTF_8)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} free-attempt-id
  "The id of the first attempt at `basis` with no record in `dir`. Ids
   repeat across processes, so two that race for the same attempt collide
   when they create its record and only one proceeds. Returns a storage
   anomaly, or :exhausted after `max-attempts` recorded attempts."
  [dir basis]
  (loop [n 0]
    (if (= n max-attempts)
      (anomaly/anomaly :exhausted (msg/t :attempt/exhausted) {:effect/basis basis})
      (let [id (attempt-id basis n)
            t (store/read-record dir id)]
        (cond
          (anomaly/anomaly? t) t
          (nil? t) id
          :else (recur (inc n)))))))

(defn ^{:stratum 1} open
  "The first attempt at `basis` that may still run, read from `dir`:
   `{:effect/id id}` for an attempt with no record, or
   `{:effect/existing record}` for the first one whose record is in any
   state but :failed. A :failed attempt definitely did not happen and is
   skipped. Returns a storage anomaly, or :exhausted once
   `max-attempts` attempts have all failed."
  [dir basis]
  (loop [n 0]
    (if (= n max-attempts)
      (anomaly/anomaly :exhausted (msg/t :attempt/exhausted) {:effect/basis basis})
      (let [id (attempt-id basis n)
            t (store/read-record dir id)]
        (cond
          (anomaly/anomaly? t) t
          (nil? t) {:effect/id id}
          (= :failed (:effect/state t)) (recur (inc n))
          :else {:effect/existing t})))))

(comment
  (attempt-id [:effect/merge (random-uuid)] 0))
