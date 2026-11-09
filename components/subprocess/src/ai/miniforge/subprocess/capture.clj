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
(ns ai.miniforge.subprocess.capture
  "Bounded drain of one child-process stream: keep at most N bytes, read
   and discard the rest, and record that the limit was passed."
  (:require
   [ai.miniforge.subprocess.config :as config]
   [ai.miniforge.subprocess.messages :as msg])
  (:import
   (java.io ByteArrayOutputStream IOException InputStream)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} output-limit-exit-code
  "128 + SIGPIPE (13): what a shell reports for a writer whose reader
   stopped reading, which is what a capture past its limit does."
  141)

(def ^{:stratum 0} read-chunk-bytes
  "Bytes per read: 8 KiB, the buffer size java.io's own stream copies use."
  8192)

(defn- ^{:stratum 0} no-op [])

(defn- ^{:stratum 0} keep-chunk!
  "Keep what still fits of the first `n` bytes of `chunk`. The first chunk
   that does not fit marks the handle overflowed and calls :on-overflow."
  [{:keys [^ByteArrayOutputStream buffer max-bytes overflow on-overflow]} ^bytes chunk n]
  (let [kept (min n (- max-bytes (.size buffer)))]
    (.write buffer chunk 0 (int kept))
    (when (and (< kept n) (compare-and-set! overflow false true))
      (on-overflow))))

(defn- ^{:stratum 0} read-chunk
  "The next read into `chunk`, or -1 at end-of-stream. A pipe read fails
   only once the stream is closed, by `cancel-drain!` or by Process.destroy
   (which closes all three of a child's streams, so stopping a child for
   one stream's overflow also closes its other stream), and both callers
   are done with the stream by then: a failed read ends the drain like
   end-of-stream."
  [{:keys [^InputStream stream]} ^bytes chunk]
  (try
    (.read stream chunk)
    ;; Plain try: this component has no slingshot dependency (rule 211 a).
    (catch IOException _ -1)))

(defn ^{:stratum 0} drained-bytes
  ^bytes [{:keys [^ByteArrayOutputStream buffer]}]
  (.toByteArray buffer))

(defn ^{:stratum 0} overflowed?
  [{:keys [overflow]}]
  @overflow)

(defn ^{:stratum 0} capture-limit
  [{:keys [max-bytes]}]
  max-bytes)

(defn ^{:stratum 0} cancel-drain!
  [{:keys [^InputStream stream future]}]
  (try
    (.close stream)
    ;; Plain try: this component has no slingshot dependency (rule 211 a).
    (catch IOException _
      ;; Already closed at end-of-stream, or the pipe is gone.
      nil))
  (future-cancel future))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} drained-text
  [{:keys [^ByteArrayOutputStream buffer max-bytes] :as handle}]
  (cond-> (.toString buffer "UTF-8")
    (overflowed? handle) (str (msg/t :capture/cut-note {:bytes max-bytes}))))

(defn- ^{:stratum 1} copy-bounded!
  "Read `stream` to end-of-stream, keeping what fits. Reading on past the
   limit keeps the child from blocking on a full pipe."
  [handle]
  (let [chunk (byte-array read-chunk-bytes)]
    (loop []
      (let [n (read-chunk handle chunk)]
        (when-not (neg? n)
          (keep-chunk! handle chunk n)
          (recur))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} drain-stream
  [^InputStream stream opts]
  (let [max-bytes   (get opts :max-bytes config/max-captured-bytes)
        on-overflow (get opts :on-overflow no-op)
        handle      {:buffer      (ByteArrayOutputStream.)
                     :stream      stream
                     :max-bytes   max-bytes
                     :overflow    (atom false)
                     :on-overflow on-overflow}]
    (assoc handle :future (future
                            (try
                              (copy-bounded! handle)
                              (finally (.close stream)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (overflowed? (drain-stream (java.io.ByteArrayInputStream. (byte-array 0)) {}))
  :leave-this-here)
