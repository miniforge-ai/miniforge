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
(ns ai.miniforge.event-stream.sinks.file
  "The file sink: writes each event as its own Transit-JSON file under
   the events directory (layout in `sinks`, file names in
   `sinks.naming`), and starts one background retention pass
   (`sinks.cleanup`) when it is created."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.sink-durability :as durability]
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.sinks.cleanup :as cleanup]
   [ai.miniforge.event-stream.sinks.naming :as naming]
   [ai.miniforge.file-durability.interface :as disk]
   [clojure.java.io :as io])
  (:import
   [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} publish-durably!
  "Write `bytes` to `tmp`, rename it to `target` atomically, and force the
   bytes and the destination's directory entries to stable storage before
   returning, so an acknowledged event survives a host crash. Throws when
   any step fails; the caller removes `tmp`."
  [^java.io.File tmp ^java.io.File target bytes]
  (let [published (disk/publish-bytes!
                   tmp bytes
                   (fn []
                     (Files/move (.toPath tmp) (.toPath target)
                                 (into-array StandardCopyOption
                                             [StandardCopyOption/ATOMIC_MOVE
                                              StandardCopyOption/REPLACE_EXISTING]))
                     target))]
    (when (anomaly/any-anomaly? published)
      (throw (ex-info (str "Event file not persisted: " (:anomaly/message published))
                      {:anomaly published})))
    published))

(defn ^{:stratum 0} workflow-dir
  "Return the per-workflow directory for an in-flight workflow. As of
   BD-2b sub-3b this is an alias for `live-workflow-dir` —
   `{base-dir}/live/{workflow-id}/`. The file sink for event files
   and the manifest module (sub-2) both reference the same path so
   archival's `mv live → archived` rename moves them together."
  (^java.io.File [workflow-id]
   (sinks/live-workflow-dir workflow-id))
  (^java.io.File [base-dir workflow-id]
   (sinks/live-workflow-dir base-dir workflow-id)))

(defn ^{:stratum 0} event-file-path
  "Return a java.io.File for a new event file in the per-workflow subdirectory.
   Creates the subdirectory if needed. Filename derives from `event`'s
   `:event/id` and `:event/sequence-number` (BD-2b filename grammar).

   File layout (BD-2b sub-3b):
     {base-dir}/live/{workflow-id}/{event-id-hex16}__{seq:012d}.transit.json
                                  (or legacy {timestamp}-{uuid}.json for
                                   non-snowflake streams).
   The archive operation moves the whole `live/{wid}/` directory to
   `archived/{wid}/` on terminal status.

   Arguments:
     base-dir    - Base directory (default: ~/.miniforge/events)
     workflow-id - UUID or string workflow identifier
     event       - The event whose envelope provides the filename fields"
  ([workflow-id event]
   (event-file-path (sinks/default-events-dir) workflow-id event))
  ([base-dir workflow-id event]
   (naming/new-event-file-path (sinks/live-workflow-dir base-dir workflow-id) event)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} file-sink
  "Create a file sink that writes each event as a Transit-JSON file.

   File layout:
     per-workflow:   {base-dir}/{workflow-id}/{event-id-hex16}__{seq:012d}.transit.json
     cross-workflow: {base-dir}/operator/{event-id-hex16}__{seq:012d}.transit.json

   The leading hex sorts by creation order when the event's :event/id is a
   snowflake-encoded UUID (BD-2b). For events whose :event/id is a random
   UUID (e.g. streams without a snowflake generator), the filename falls
   back to the legacy {timestamp}-{uuid}.json shape so sort-by-creation
   still works via the timestamp prefix.

   Each event gets its own file (no append).

   Starts one background `cleanup-stale-events!` pass on creation (see
   it for what is pruned and what never is).

   Arguments:
     opts - Map with optional:
       :base-dir - Base directory (default: ~/.miniforge/events)
       :ttl-ms   - Retention for that cleanup pass in ms (default: 7 days)

   Returns: a durable sink function (fn [event]). It returns after the
   event's bytes and directory entries are forced to stable storage. A
   failed write prints a warning to stderr and rethrows, so the stream logs it and
   records that the event was not persisted (`publish-persisted!` refuses
   it); the stream's other sinks and subscribers still run."
  [& [opts]]
  ;; The directory is resolved once, at creation (an explicit nil
  ;; :base-dir included): the background cleanup and every write use the
  ;; one the sink was created for, never one resolved later (a test
  ;; pointing the default at a temp dir while it creates the sink keeps
  ;; the cleanup there too).
  (let [base-dir (or (:base-dir opts) (sinks/default-events-dir))]
    ;; Non-blocking cleanup on sink creation
    (future (try (cleanup/cleanup-stale-events! (update opts :events-dir #(or % base-dir)))
                 (catch Exception _ nil)))
    (durability/durable-sink
     (fn [event]
       (try
         (let [file-path (if-let [workflow-id (:workflow/id event)]
                           (event-file-path base-dir workflow-id event)
                           (sinks/operator-event-file-path base-dir event))
               f   (io/file file-path)
               dir (or (.getParentFile f) (io/file "."))
               _   (.mkdirs dir)
               tmp (java.io.File/createTempFile ".es-" ".transit.tmp" dir)]
           (try
             ;; Explicit UTF-8: payload strings can carry non-ASCII and these
             ;; files are a cross-language contract surface — platform default
             ;; encoding must never decide the on-disk bytes.
             (publish-durably! tmp f (.getBytes ^String (sinks/event->transit-json event) "UTF-8"))
             (catch Throwable e
               (try (Files/deleteIfExists (.toPath tmp))
                    (catch Throwable cleanup-e (.addSuppressed e cleanup-e)))
               (throw e))))
         (catch Exception e
           ;; stderr keeps the failure visible on a stream with no logger; the
           ;; rethrow tells the stream this durable sink did not persist it.
           (binding [*out* *err*]
             (println (str "WARNING: Event sink write failed: " (ex-message e))))
           (throw e)))))))
