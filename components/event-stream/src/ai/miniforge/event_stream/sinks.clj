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
(ns ai.miniforge.event-stream.sinks
  "Where the event store lives on disk, and how an event file is
   encoded. The file sink writes this layout; the archive, the readers
   and the operator request writer read it:

     {events-dir}/live/{workflow-id}/      in-flight workflows
     {events-dir}/archived/{workflow-id}/  archived workflows
     {events-dir}/operator/                cross-workflow operator events

   {events-dir} defaults to ~/.miniforge/events. The sinks are in
   `sinks.factory` (create-sink, create-sinks-from-config), `sinks.file`,
   `sinks.console` and `sinks.fleet`; event file names are in
   `sinks.naming` and stored-event retention in `sinks.cleanup`."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.sinks.naming :as naming]
   [ai.miniforge.event-stream.storage-layout :as layout]
   [clojure.java.io :as io]
   [cognitect.transit :as transit])
  (:import
   [java.io ByteArrayOutputStream]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} default-events-dir
  "Return the default events directory (~/.miniforge/events). Public
   so the BD-2b sub-3b archive module and the cleanup pass (sub-3c)
   can resolve the same root the file sink writes under."
  ^java.io.File []
  (io/file (config/miniforge-home) "events"))

(def ^{:stratum 0} ^:private instant-write-handler
  "Transit write handler for java.time.Instant — writes the instant's
   RFC-3339 string under tag `t`, surfacing as a `~t...` tag-string in
   verbose Transit JSON (same shape Transit gives java.util.Date in
   verbose mode)."
  (transit/write-handler
   (constantly "t")
   (fn [^Instant v] (str v))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} event->transit-json
  "Serialize `event` to a Transit-JSON string using the verbose writer.
   Verbose mode disables transit's cache codes (repeated keywords stay
   literal, never `^0` references) and renders instants as RFC-3339
   `~t...` tag-strings instead of compact `~m` millisecond strings, so
   the Rust parser can decode them without millisecond arithmetic.
   UUIDs render as `~u...` tag-strings. Custom handler for
   java.time.Instant (not supported by Transit defaults).

   Public: this is the canonical wire encoding for every event the file
   sink persists. Contract tooling (supervisory golden fixtures) calls it
   directly so vendored fixtures share the exact byte path with production."
  [event]
  (let [out (ByteArrayOutputStream.)
        writer (transit/writer out :json-verbose
                               {:handlers {Instant instant-write-handler}})]
    (transit/write writer event)
    (.toString out "UTF-8")))

(defn ^{:stratum 1} live-dir
  "Return the `{base-dir}/live/` directory that holds in-flight
   workflows. Public so the archive module and cleanup pass can scan
   it on boot for recovery / cleanup."
  (^java.io.File [] (live-dir (default-events-dir)))
  (^java.io.File [base-dir] (io/file base-dir (layout/live-subdir))))

(defn ^{:stratum 1} archived-dir
  "Return the `{base-dir}/archived/` directory that holds workflows
   whose archive completed via BD-2b sub-3b's atomic move."
  (^java.io.File [] (archived-dir (default-events-dir)))
  (^java.io.File [base-dir] (io/file base-dir (layout/archived-subdir))))

(defn ^{:stratum 1} operator-dir
  "Return the cross-workflow operator events directory under `base-dir`.
   Writer side is [[operator-event-file-path]]; the operator-event
   consumer (Phase D D-2) lists this directory on the read side."
  (^java.io.File []
   (operator-dir (default-events-dir)))
  (^java.io.File [base-dir]
   (io/file base-dir (layout/operator-subdir))))

(defn ^{:stratum 1} operator-event-file-path
  "Return a java.io.File for a new event file in the operator subdirectory.
   Used by meta-loop, reliability, and degradation events (no :workflow/id).

   File layout: {base-dir}/operator/{event-id-hex16}__{seq:012d}.transit.json
                (or legacy {timestamp}-{uuid}.json for non-snowflake streams).

   Arguments:
     base-dir - Base directory (default: ~/.miniforge/events)
     event    - The event whose envelope provides the filename fields"
  ([event]
   (operator-event-file-path (default-events-dir) event))
  ([base-dir event]
   (naming/new-event-file-path (io/file base-dir (layout/operator-subdir)) event)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} live-workflow-dir
  "Return `{base-dir}/live/{workflow-id}/`. Canonical path for an
   in-flight workflow's event files + manifest."
  (^java.io.File [workflow-id]
   (live-workflow-dir (default-events-dir) workflow-id))
  (^java.io.File [base-dir workflow-id]
   (io/file (live-dir base-dir) (naming/workflow-id-segment workflow-id))))

(defn ^{:stratum 2} archived-workflow-dir
  "Return `{base-dir}/archived/{workflow-id}/`. The destination of
   the BD-2b sub-3b atomic archive operation."
  (^java.io.File [workflow-id]
   (archived-workflow-dir (default-events-dir) workflow-id))
  (^java.io.File [base-dir workflow-id]
   (io/file (archived-dir base-dir) (naming/workflow-id-segment workflow-id))))
