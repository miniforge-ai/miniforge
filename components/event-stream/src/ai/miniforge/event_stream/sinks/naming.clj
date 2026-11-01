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
(ns ai.miniforge.event-stream.sinks.naming
  "Names the event store gives things on disk: the directory segment for
   a workflow id, and the file name for an event — snowflake
   {event-id-hex16}__{workflow-seq-dec12}.transit.json, or legacy
   {timestamp}-{uuid}.json. `event-file-ms` reads the time back out of
   either name."
  (:require
   [ai.miniforge.event-stream.snowflake :as snowflake]
   [clojure.java.io :as io])
  (:import
   [java.time LocalDateTime ZonedDateTime ZoneOffset]
   [java.time.format DateTimeFormatter]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} now-sortable-str
  "Return a sortable UTC timestamp string for use as a file-name prefix.
   Format: yyyyMMdd'T'HHmmss'Z', e.g. 20260411T100000Z."
  []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
           (ZonedDateTime/now ZoneOffset/UTC)))

(defn- ^{:stratum 0} snowflake-uuid?
  "True when `id` is a UUID whose low 64 bits are zero — the shape that
   `snowflake/long->uuid` produces. Random UUIDs collide on this with
   probability ~1 in 2^64, which is negligible."
  [id]
  (and (uuid? id)
       (zero? (.getLeastSignificantBits ^java.util.UUID id))))

(defn- ^{:stratum 0} safe-path-segment
  "`s` when it can only name one entry inside its parent: letters, digits,
   `.`, `_` and `-`, starting with a letter or digit (so never `.`, `..`,
   a hidden name or anything with a separator). Otherwise `id-` plus the
   first 16 hex digits of its SHA-256: the same input always maps to the
   same name, and nothing an event carries can climb out of the events
   directory. Ids reach here from the unauthenticated ingest endpoint."
  ^String [^String s]
  (if (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" s)
    s
    (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                          (.getBytes s "UTF-8"))]
      (str "id-" (subs (apply str (map #(format "%02x" %) digest)) 0 16)))))

(defn ^{:stratum 0} event-file-ms
  "The epoch ms an event file name records, inverting `event-filename`:
   a legacy `{yyyyMMddTHHmmss[SSS]Z}-{uuid}.json` timestamp, or a
   `{snowflake-hex16}__{seq12}.transit.json` id's timestamp bits. Orders
   a directory mixing the grammars; nil for any other name."
  [^String file-name]
  (if-let [[_ ts ms] (re-matches #"(\d{8}T\d{6})(\d{3})?Z-[^/]+\.json" file-name)]
    (+ (.toEpochMilli (.toInstant (LocalDateTime/parse ts (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss"))
                                  ZoneOffset/UTC))
       (if ms (Long/parseLong ms) 0))
    (when-let [[_ hex] (re-matches #"([0-9a-f]{16})__\d{12}\.transit\.json" file-name)]
      (+ snowflake/miniforge-epoch-ms
         (bit-shift-right (Long/parseUnsignedLong hex 16) snowflake/ts-shift)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} workflow-id-segment
  "Normalize a workflow-id into a path segment safe for the OS
   filesystem. Keywords become their plain name (`:canonical-sdlc` →
   `canonical-sdlc`) — `(str ...)` would otherwise include the colon
   prefix, which is invalid on Windows and creates surprising layouts
   elsewhere. UUIDs and plain names pass through unchanged; anything else
   goes through `safe-path-segment`. Reused by `workflow-dir` and
   `event-file-path` so on-disk path normalisation is consistent across
   all callers."
  ^String [workflow-id]
  (safe-path-segment (if (keyword? workflow-id)
                       (name workflow-id)
                       (str workflow-id))))

(defn- ^{:stratum 1} event-filename
  "Pick a filename for `event`.

   New (BD-2b): when `:event/id` is a snowflake UUID,
     `{event-id-hex16}__{workflow-seq-dec12}.transit.json`
   sorts lex-by-creation across all workflows on this host.

   Legacy: `{timestamp}-{uuid}.json` for streams without a snowflake
   generator. Sort-by-creation still works via the timestamp prefix."
  [event]
  (let [id (:event/id event)
        seq-num (long (or (:event/sequence-number event) 0))]
    (if (snowflake-uuid? id)
      (str (snowflake/uuid->hex id)
           "__"
           (format "%012d" seq-num)
           ".transit.json")
      (str (now-sortable-str) "-" (safe-path-segment (str (or id (random-uuid)))) ".json"))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} new-event-file-path
  "Return a java.io.File for `event` inside `parent-dir`. Creates the
   directory if needed. The filename is derived from the event itself
   (BD-2b filename grammar — see `event-filename`)."
  [parent-dir event]
  (let [dir (io/file parent-dir)]
    (.mkdirs dir)
    (io/file dir (event-filename event))))
