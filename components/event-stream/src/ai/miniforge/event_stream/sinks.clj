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
  "Configurable event sinks for different deployment scenarios.

   Supported sinks:
   - :file   - Write to ~/.miniforge/events/<workflow-id>/<filename>
               (local dev). The filename grammar is conditional on the
               envelope's :event/id:
                 * Snowflake-encoded UUID (low 64 bits zero, BD-2b) →
                   {event-id-hex16}__{workflow-seq-dec12}.transit.json,
                   sortable lex by creation order across workflows;
                 * any other UUID (no snowflake generator wired into the
                   stream, or legacy files on disk) →
                   {timestamp}-{uuid}.json, sortable by the ts prefix.
               Both shapes coexist; the reader accepts either.
   - :stdout - Print to stdout (container/Docker/K8s)
   - :stderr - Print to stderr (error-only events)
   - :fleet  - Send to fleet command (org-level ops)
   - :multi  - Combine multiple sinks"
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.event-stream.snowflake :as snowflake]
   [ai.miniforge.event-stream.storage-layout :as layout]
   [ai.miniforge.logging.interface :as logging]
   [ai.miniforge.response.interface :as response]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.pprint :as pprint]
   [cognitect.transit :as transit]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io ByteArrayOutputStream]
   [java.net.http HttpClient HttpResponse$BodyHandlers]
   [java.time Instant ZonedDateTime ZoneOffset]
   [java.time.format DateTimeFormatter]))

;;------------------------------------------------------------------------------ Internal helpers

(defn default-events-dir
  "Return the default events directory (~/.miniforge/events). Public
   so the BD-2b sub-3b archive module and the cleanup pass (sub-3c)
   can resolve the same root the file sink writes under."
  ^java.io.File []
  (io/file (config/miniforge-home) "events"))

(defn- now-sortable-str
  "Return a sortable UTC timestamp string for use as a file-name prefix.
   Format: yyyyMMdd'T'HHmmss'Z', e.g. 20260411T100000Z."
  []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
           (ZonedDateTime/now ZoneOffset/UTC)))

(def ^:private instant-write-handler
  "Transit write handler for java.time.Instant — writes the instant's
   RFC-3339 string under tag `t`, surfacing as a `~t...` tag-string in
   verbose Transit JSON (same shape Transit gives java.util.Date in
   verbose mode)."
  (transit/write-handler
   (constantly "t")
   (fn [^Instant v] (str v))))

(defn event->transit-json
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

(defn- snowflake-uuid?
  "True when `id` is a UUID whose low 64 bits are zero — the shape that
   `snowflake/long->uuid` produces. Random UUIDs collide on this with
   probability ~1 in 2^64, which is negligible."
  [id]
  (and (uuid? id)
       (zero? (.getLeastSignificantBits ^java.util.UUID id))))

(defn- event-filename
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
      (str (now-sortable-str) "-" (or id (random-uuid)) ".json"))))

(defn- new-event-file-path
  "Return a java.io.File for `event` inside `parent-dir`. Creates the
   directory if needed. The filename is derived from the event itself
   (BD-2b filename grammar — see `event-filename`)."
  [parent-dir event]
  (let [dir (io/file parent-dir)]
    (.mkdirs dir)
    (io/file dir (event-filename event))))

(defn- ensure-parent-dir!
  [file-path]
  (some-> file-path .getParentFile .mkdirs))

;;------------------------------------------------------------------------------ Layer 0: File Sink paths

(defn- workflow-id-segment
  "Normalize a workflow-id into a path segment safe for the OS
   filesystem. Keywords become their plain name (`:canonical-sdlc` →
   `canonical-sdlc`) — `(str ...)` would otherwise include the colon
   prefix, which is invalid on Windows and creates surprising layouts
   elsewhere. UUIDs / strings pass through `str` unchanged. Reused by
   `workflow-dir` and `event-file-path` so on-disk path normalisation
   is consistent across all callers."
  ^String [workflow-id]
  (if (keyword? workflow-id)
    (name workflow-id)
    (str workflow-id)))

(defn live-dir
  "Return the `{base-dir}/live/` directory that holds in-flight
   workflows. Public so the archive module and cleanup pass can scan
   it on boot for recovery / cleanup."
  (^java.io.File [] (live-dir (default-events-dir)))
  (^java.io.File [base-dir] (io/file base-dir (layout/live-subdir))))

(defn archived-dir
  "Return the `{base-dir}/archived/` directory that holds workflows
   whose archive completed via BD-2b sub-3b's atomic move."
  (^java.io.File [] (archived-dir (default-events-dir)))
  (^java.io.File [base-dir] (io/file base-dir (layout/archived-subdir))))

(defn live-workflow-dir
  "Return `{base-dir}/live/{workflow-id}/`. Canonical path for an
   in-flight workflow's event files + manifest."
  (^java.io.File [workflow-id]
   (live-workflow-dir (default-events-dir) workflow-id))
  (^java.io.File [base-dir workflow-id]
   (io/file (live-dir base-dir) (workflow-id-segment workflow-id))))

(defn archived-workflow-dir
  "Return `{base-dir}/archived/{workflow-id}/`. The destination of
   the BD-2b sub-3b atomic archive operation."
  (^java.io.File [workflow-id]
   (archived-workflow-dir (default-events-dir) workflow-id))
  (^java.io.File [base-dir workflow-id]
   (io/file (archived-dir base-dir) (workflow-id-segment workflow-id))))

(defn workflow-dir
  "Return the per-workflow directory for an in-flight workflow. As of
   BD-2b sub-3b this is an alias for `live-workflow-dir` —
   `{base-dir}/live/{workflow-id}/`. The file sink for event files
   and the manifest module (sub-2) both reference the same path so
   archival's `mv live → archived` rename moves them together."
  (^java.io.File [workflow-id]
   (live-workflow-dir workflow-id))
  (^java.io.File [base-dir workflow-id]
   (live-workflow-dir base-dir workflow-id)))

(defn event-file-path
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
   (event-file-path (default-events-dir) workflow-id event))
  ([base-dir workflow-id event]
   (new-event-file-path (live-workflow-dir base-dir workflow-id) event)))

(defn operator-event-file-path
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
   (new-event-file-path (io/file base-dir (layout/operator-subdir)) event)))

(defn cleanup-stale-events!
  "Delete event files older than TTL from the events directory.
   Walks all subdirectories (per-workflow and operator).

   Arguments:
     opts - Map with optional:
       :events-dir - Directory to clean (default: ~/.miniforge/events)
       :base-dir   - Alias for :events-dir
       :ttl-ms     - Max age in milliseconds (default: 7 days)

   Returns: Number of files deleted"
  [& [opts]]
  (let [ttl-ms (get opts :ttl-ms (* 7 24 60 60 1000))
        events-dir (or (:events-dir opts) (:base-dir opts) (default-events-dir))
        cutoff (- (System/currentTimeMillis) ttl-ms)]
    (if (.isDirectory events-dir)
      (let [stale-files (->> (file-seq events-dir)
                             (filter #(and (.isFile %)
                                           (str/ends-with? (.getName %) ".json")
                                           (< (.lastModified %) cutoff))))]
        (doseq [f stale-files] (.delete f))
        (count stale-files))
      0)))

(defn file-sink
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

   Performs lazy cleanup of stale event files (older than 7 days) on creation.

   Arguments:
     opts - Map with optional:
       :base-dir - Base directory (default: ~/.miniforge/events)
       :ttl-ms   - Max event file age in ms (default: 7 days)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  ;; Non-blocking cleanup on sink creation
  (future (try (cleanup-stale-events! opts) (catch Exception _ nil)))
  (fn [event]
    (try
      (let [base-dir (or (:base-dir opts) (default-events-dir))
            file-path (if-let [workflow-id (:workflow/id event)]
                        (event-file-path base-dir workflow-id event)
                        (operator-event-file-path base-dir event))]
        (ensure-parent-dir! file-path)
        ;; Explicit UTF-8: payload strings can carry non-ASCII and these
        ;; files are a cross-language contract surface — platform default
        ;; encoding must never decide the on-disk bytes.
        (spit file-path (event->transit-json event) :encoding "UTF-8"))
      (catch Exception e
        ;; Log to stderr so failures are visible without breaking the event stream
        (binding [*out* *err*]
          (println (str "WARNING: Event sink write failed: " (ex-message e))))
        nil))))

;;------------------------------------------------------------------------------ Layer 1: Stream Sinks

(defn stdout-sink
  "Create a stdout sink that prints events to standard output.

   Useful for containers where stdout is collected by log aggregators.

   Arguments:
     opts - Map with optional:
       :format - :edn or :json (default :edn)
       :compact - Boolean for compact output (default false)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  (let [format (:format opts :edn)
        compact? (:compact opts false)]
    (fn [event]
      (try
        (let [output (case format
                       :edn (if compact?
                              (pr-str event)
                              (with-out-str (pprint/pprint event)))
                       :json (let [gen-str (requiring-resolve 'cheshire.core/generate-string)]
                               (str (gen-str event {:pretty (not compact?)})))
                       (pr-str event))]
          (println output)
          (flush))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "WARNING: Event sink write failed: " (ex-message e)))))))))

(defn stderr-sink
  "Create a stderr sink that prints events to standard error.

   Useful for error/warning events that should go to stderr stream.

   Arguments:
     opts - Map with optional:
       :filter - Predicate (fn [event] -> bool) to filter events
       :format - :edn or :json (default :edn)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  (let [filter-fn (:filter opts (constantly true))
        format (:format opts :edn)]
    (fn [event]
      (when (filter-fn event)
        (try
          (let [output (case format
                         :edn (pr-str event)
                         :json (let [gen-str (requiring-resolve 'cheshire.core/generate-string)]
                                 (gen-str event))
                         (pr-str event))]
            (binding [*out* *err*]
              (println output)))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "WARNING: Event sink write failed: " (ex-message e))))))))))

;;------------------------------------------------------------------------------ Layer 2: Fleet Sink

(defn fleet-sink
  "Create a fleet sink that sends events to fleet command via HTTP.

   Arguments:
     opts - Map with required:
       :url - Fleet command URL (e.g., https://fleet.miniforge.ai)
       :api-key - API key for authentication
     Optional:
       :batch-size - Number of events to batch (default 10)
       :flush-interval-ms - Max time before flushing (default 5000)
       :timeout-ms - HTTP timeout (default 10000)
       :http-client - java.net.http.HttpClient instance (default: HttpClient/newHttpClient)

   Returns: Sink function (fn [event] -> nil)"
  [opts]
  ;; Missing-config throws are PROGRAMMER ERROR guards per standards
  ;; rule 005 §programmer-error-guards: IllegalArgumentException is the
  ;; rule-prescribed shape (not throw-anomaly! — that's for boundary
  ;; namespaces, and `event-stream.sinks` is not one). The constructor
  ;; has no return-value channel to surface an anomaly map without
  ;; breaking caller expectations of a callable sink.
  (let [url         (or (:url opts)
                        (throw (IllegalArgumentException.
                                 (messages/t :fleet-sink.system/missing-url))))
        api-key          (or (:api-key opts)
                             (throw (IllegalArgumentException.
                                      (messages/t :fleet-sink.system/missing-api-key))))
        batch-size       (:batch-size opts 10)
        flush-interval-ms (:flush-interval-ms opts 5000)
        timeout-ms       (:timeout-ms opts 10000)
        http-client      (or (:http-client opts) (HttpClient/newHttpClient))
        batch-atom       (atom [])
        last-flush-atom  (atom (System/currentTimeMillis))]

    (letfn [(flush-batch! []
              ;; Atomically drain the batch so concurrent senders don't race
              ;; on @batch-atom + reset!. swap-vals! returns [prev new] —
              ;; first prev = the snapshot we own. Events appended while
              ;; the HTTP call is in flight go into the FRESH atom value
              ;; and are picked up by the next flush.
              (let [events (first (swap-vals! batch-atom (constantly [])))]
                (when (seq events)
                  ;; try+ per standards rule 211 — boundary catch around
                  ;; Java HttpClient.send. The component's other plain
                  ;; `try` sites (file write, mkdirs, etc.) predate this
                  ;; rule and are migrated opportunistically (211 §migration).
                  (try+
                    (let [body     (json/generate-string {:events events})
                          request  (logging/build-json-post (str url "/events") body api-key timeout-ms)
                          response (.send http-client request (HttpResponse$BodyHandlers/discarding))]
                      (when (>= (.statusCode response) 400)
                        (binding [*out* *err*]
                          (println (messages/t :fleet-sink.system/http-error
                                               {:url    (str url "/events")
                                                :status (.statusCode response)})))))
                    (catch InterruptedException e
                      ;; Preserve interrupt semantics for callers.
                      (.interrupt (Thread/currentThread))
                      (binding [*out* *err*]
                        (println (messages/t :fleet-sink.system/interrupted
                                             {:error (ex-message e)}))))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (messages/t :fleet-sink.system/flush-error
                                             {:error (ex-message e)}))))
                    (finally
                      (reset! last-flush-atom (System/currentTimeMillis)))))))]

      (fn [event]
        (swap! batch-atom conj event)
        (let [now (System/currentTimeMillis)
              time-since-flush (- now @last-flush-atom)
              batch-full? (>= (count @batch-atom) batch-size)
              time-exceeded? (>= time-since-flush flush-interval-ms)]
          (when (or batch-full? time-exceeded?)
            (flush-batch!)))))))

;;------------------------------------------------------------------------------ Layer 3: Multi-Sink

(defn multi-sink
  "Create a multi-sink that writes to multiple sinks simultaneously.

   Arguments:
     sinks - Vector of sink functions

   Returns: Combined sink function (fn [event] -> nil)"
  [sinks]
  (fn [event]
    (doseq [sink sinks]
      (try
        (sink event)
        (catch Exception _e
          ;; Continue with other sinks even if one fails
          nil)))))

;;------------------------------------------------------------------------------ Layer 4: Sink Factory

(defn create-sink
  "Create a sink from configuration.

   Arguments:
     sink-config - Keyword or map:
       :file - File sink
       :stdout - Stdout sink
       :stderr - Stderr sink
       {:type :fleet :url \"...\" :api-key \"...\"} - Fleet sink
       {:type :multi :sinks [...]} - Multi-sink

   Returns: Sink function (fn [event] -> nil)"
  [sink-config]
  (cond
    ;; Keyword shortcuts
    (= sink-config :file) (file-sink)
    (= sink-config :stdout) (stdout-sink)
    (= sink-config :stderr) (stderr-sink {:filter (fn [e]
                                                     (contains? #{:error :workflow/failed}
                                                               (:event/type e)))})

    ;; Map configurations
    (map? sink-config)
    (case (:type sink-config)
      :file (file-sink (dissoc sink-config :type))
      :stdout (stdout-sink (dissoc sink-config :type))
      :stderr (stderr-sink (dissoc sink-config :type))
      :fleet (fleet-sink (dissoc sink-config :type))
      :multi (multi-sink (map create-sink (:sinks sink-config)))
      (response/throw-anomaly! :anomalies/unsupported
                               "Unknown sink type"
                               {:type (:type sink-config)}))

    ;; Vector - treat as multi-sink
    (vector? sink-config)
    (multi-sink (map create-sink sink-config))

    :else
    (response/throw-anomaly! :anomalies/incorrect
                             "Invalid sink configuration"
                             {:config sink-config})))

(defn create-sinks-from-config
  "Create sinks from user configuration.

   Arguments:
     config - Config map with :observability {:event-sinks [...]}

   Returns: Vector of sink functions

   Example config:
     {:observability {:event-sinks [:file :stdout]
                      :fleet {:url \"https://fleet.miniforge.ai\"
                              :api-key \"key123\"}}}

   Environment variables:
     MINIFORGE_EVENT_SINKS=file,stdout
     MINIFORGE_FLEET_URL=https://fleet.miniforge.ai
     MINIFORGE_FLEET_API_KEY=key123"
  [config]
  (let [;; Get sinks from config or env var
        sinks-config (or (get-in config [:observability :event-sinks])
                        (when-let [env (System/getenv "MINIFORGE_EVENT_SINKS")]
                          (mapv keyword (str/split env #",")))
                        [:file]) ;; Default to file sink

        ;; Get fleet config from config or env vars
        fleet-config (or (get-in config [:observability :fleet])
                        (when (System/getenv "MINIFORGE_FLEET_URL")
                          {:url (System/getenv "MINIFORGE_FLEET_URL")
                           :api-key (System/getenv "MINIFORGE_FLEET_API_KEY")}))

        ;; Replace :fleet keyword with full fleet config
        sinks-config (mapv (fn [sink]
                            (if (= sink :fleet)
                              (assoc fleet-config :type :fleet)
                              sink))
                          sinks-config)]
    (mapv create-sink sinks-config)))
