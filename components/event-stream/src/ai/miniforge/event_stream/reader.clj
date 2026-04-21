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

(ns ai.miniforge.event-stream.reader
  "Read event streams back from the on-disk file sink.

   Paired with `sinks/file-sink`. The sink writes one transit-JSON
   file per event at `{base-dir}/{workflow-id}/{timestamp}-{uuid}.json`
   (timestamp-sortable). This namespace loads that directory back into
   a sorted sequence of parsed event maps.

   Consumers:
   - `mf events show` — human-readable timeline
   - `mf run --resume` — reconstructs execution context from event history
   - dashboard replays, evidence bundling

   The transit-JSON encoding prefixes keyword keys with `~:` and
   UUIDs/instants with `~u`/`~t`. `strip-transit-prefix` walks the
   parsed structure and turns those strings back into keywords /
   stripped strings so downstream code can use keyword accessors."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Transit-JSON prefix stripping

(defn strip-transit-prefix
  "Turn the raw transit-JSON parse back into idiomatic Clojure data.

   - strings prefixed with `~:` become keywords
   - `~u` / `~t` prefixes (UUIDs, instants) become the stripped string
     form — callers that need typed values can parse further
   - maps and vectors are walked recursively

   Keeps the shape transit emits without requiring the transit
   library on callers' classpaths."
  [x]
  (cond
    (map? x)
    (reduce-kv (fn [acc k v]
                 (let [k' (if (and (string? k) (.startsWith ^String k "~:"))
                            (keyword (subs k 2))
                            k)]
                   (assoc acc k' (strip-transit-prefix v))))
               {}
               x)

    (vector? x)
    (mapv strip-transit-prefix x)

    (and (string? x) (.startsWith ^String x "~:"))
    (keyword (subs x 2))

    (and (string? x) (.startsWith ^String x "~t"))
    (subs x 2)

    (and (string? x) (.startsWith ^String x "~u"))
    (subs x 2)

    :else x))

;------------------------------------------------------------------------------ Layer 1
;; Directory reader

(defn read-workflow-events
  "Read every `.json` event file under a workflow directory, sorted by
   filename (which is timestamp-prefixed). Parse each as transit-JSON
   and strip the transit prefixes.

   Arguments:
   - `dir` — java.io.File or String path to the workflow events dir
     (e.g. `~/.miniforge/events/<workflow-id>/`)

   Returns a vector of parsed event maps, or nil if the directory
   does not exist. Files that fail to parse are silently dropped —
   one corrupt event file shouldn't kill a whole replay."
  [dir]
  (let [^java.io.File dir (io/file dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName ^java.io.File %) ".json"))
           (sort-by #(.getName ^java.io.File %))
           (keep (fn [^java.io.File f]
                   (try
                     (strip-transit-prefix (json/parse-string (slurp f) false))
                     (catch Exception _e nil))))
           vec))))

(defn read-workflow-events-by-id
  "Convenience: read events for a workflow id under a base events dir.

   Arguments:
   - `base-dir` — base events directory (e.g. `~/.miniforge/events`)
   - `workflow-id` — UUID or string

   Returns a vector of parsed event maps, or nil if the workflow
   directory does not exist."
  [base-dir workflow-id]
  (read-workflow-events (io/file (str base-dir) (str workflow-id))))
