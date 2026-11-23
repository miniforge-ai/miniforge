;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.subprocess.interface
  "Bounded capture of a child process's stdout and stderr, so no stream a
   child writes can grow the JVM heap without limit."
  (:require
   [ai.miniforge.subprocess.capture :as capture]
   [ai.miniforge.subprocess.config :as config]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} max-captured-bytes
  "Most bytes `drain-stream` keeps from one stream unless told otherwise:
   :subprocess/max-captured-bytes in config/subprocess/capture.edn."
  config/max-captured-bytes)

(def ^{:stratum 0} output-limit-exit-code
  "Exit code for a child its caller stopped because a stream passed the
   capture limit. 141 is 128 + SIGPIPE, what a shell reports for a writer
   whose reader stopped reading."
  capture/output-limit-exit-code)

(defn ^{:stratum 0} drain-stream
  "Copy `stream` into a buffer on a background thread, keeping at most
   `:max-bytes` (default `max-captured-bytes`), and close it at end-of-stream.

   Bytes past the limit are read and discarded, so the child never blocks on
   a full pipe. The first time that happens the handle is marked overflowed
   and `:on-overflow`, a no-argument fn, runs on the drain thread; a caller
   that cannot use cut output passes one that stops the child.

   Returns a handle for `drained-text`, `drained-bytes`, `overflowed?` and
   `cancel-drain!`. Its `:future` completes at end-of-stream, or when the
   stream is closed under it (`cancel-drain!`, Process.destroy), keeping
   what was read; it does not fail on a closed stream."
  ([stream] (capture/drain-stream stream {}))
  ([stream opts] (capture/drain-stream stream opts)))

(defn ^{:stratum 0} read-bounded
  "`drain-stream` on the calling thread, for a caller that already reads on
   a thread of its own: read `stream` to its end with the same limit and
   `:on-overflow`, close it, and return the handle for `drained-text`,
   `drained-bytes`, `overflowed?` and `capture-limit`. The handle has no
   `:future`, so it is not for `cancel-drain!`."
  ([stream] (capture/read-bounded stream {}))
  ([stream opts] (capture/read-bounded stream opts)))

(defn ^{:stratum 0} drained-text
  "The kept bytes as UTF-8 text, ending in a note when the stream was cut,
   so a reader of the text can tell. Safe to call while the drain runs."
  [handle]
  (capture/drained-text handle))

(defn ^{:stratum 0} drained-bytes
  "The kept bytes. Safe to call while the drain still runs."
  ^bytes [handle]
  (capture/drained-bytes handle))

(defn ^{:stratum 0} overflowed?
  "True once the stream passed the handle's limit: the kept bytes are then
   only its head."
  [handle]
  (capture/overflowed? handle))

(defn ^{:stratum 0} capture-limit
  "The most bytes the handle keeps: its :max-bytes."
  [handle]
  (capture/capture-limit handle))

(defn ^{:stratum 0} cancel-drain!
  "Stop a drain that has not reached end-of-stream. A thread parked in a
   pipe read ignores interrupts, so the stream is closed first and the
   future cancelled after. A no-op on a finished drain."
  [handle]
  (capture/cancel-drain! handle))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (let [h (drain-stream (java.io.ByteArrayInputStream. (.getBytes "hello world")) {:max-bytes 5})]
    @(:future h)
    [(overflowed? h) (drained-text h)])
  :leave-this-here)
