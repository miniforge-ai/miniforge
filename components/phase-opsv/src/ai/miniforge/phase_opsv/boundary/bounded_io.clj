;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.bounded-io
  "Owned, bounded response reads; these throwing effect ports are converted at ingress."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.utf8.interface :as utf8]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent Callable Future FutureTask TimeUnit TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} deadline [timeout-ms]
  (unchecked-add (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS timeout-ms)))

(defn ^{:stratum 0} remaining-ms [until]
  (let [nanos (unchecked-subtract until (System/nanoTime))]
    (max 0 (.toMillis TimeUnit/NANOSECONDS nanos))))

(defn ^{:stratum 0} cancel! [^Future pending] (.cancel pending true))

(defn- ^{:stratum 0} capture! [result operation] (vreset! result (operation)))

(defn- ^{:stratum 0} reader [body limit]
  (FutureTask.
   ^Callable (reify Callable
               (call [_] (.readNBytes ^java.io.InputStream body (int (inc limit)))))))

(defn- ^{:stratum 0} start-reader! [^FutureTask task]
  (doto (Thread. task) (.setDaemon true) (.start)))

(defn ^{:stratum 0} utf8-text
  "Strict UTF-8 text of a response body; malformed bytes throw, like the
   other effect ports here, and are converted at ingress."
  [bytes]
  (or (utf8/decode-strict bytes)
      (throw (IllegalArgumentException.))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} await! [^Future pending until]
  (let [timeout (remaining-ms until)]
    (.get pending timeout TimeUnit/MILLISECONDS)))

(defn ^{:stratum 1} owned-result!
  "Adapt the shared throwing cleanup policy without losing the operation's value."
  [operation release!]
  (let [result (volatile! nil)]
    (cleanup/finish! (partial capture! result operation) release!)
    @result))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-bytes!
  "Read at most limit+1 bytes before the shared deadline. The caller owns
   stream closure; this scope always cancels its reader before that closure."
  [body limit until]
  (when-not (pos? (remaining-ms until)) (throw (TimeoutException.)))
  (let [pending (reader body limit)]
    (try+
      (start-reader! pending)
      (await! pending until)
      (finally (cancel! pending)))))

(comment ::bounded-response-io)
