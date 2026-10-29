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
   [java.nio.file Files LinkOption OpenOption StandardCopyOption StandardOpenOption]
   [java.time Instant LocalDateTime ZonedDateTime ZoneOffset]
   [java.time.format DateTimeFormatter]))

;------------------------------------------------------------------------------ Layer 0

;;------------------------------------------------------------------------------ Defaults
(def ^{:stratum 0} ^:private default-event-ttl-ms
  "How long cleanup keeps stored events — 7 days: an archived workflow
   for this long after its archive, an operator event or a legacy
   workflow directory for this long after its last write. Long enough to
   debug a failed run days later; short enough that the on-disk event log
   doesn't grow unbounded on a busy fleet."
  (* 7 24 60 60 1000))

(def ^{:stratum 0} ^:private warned-runs
  "Live run directories cleanup has warned about keeping, so each is
   reported once per process."
  (atom #{}))

(def ^{:stratum 0} ^:private manifest-filename
  "The per-workflow manifest's file name, read from the manifest
   contract defaults. The manifest namespace is JVM-only; this one also
   loads under Babashka, so it reads the name from the resource."
  (:manifest-filename
   (config/load-config-resource "config/event-stream/manifest-defaults.edn"
                                [:manifest-filename])))

(def ^{:stratum 0} ^:private default-fleet-sink-batch-size
  "Events buffered before the fleet HTTP sink flushes a batch. 10 keeps
   request overhead low without holding events long enough to lose many on a
   crash between flushes."
  10)

(def ^{:stratum 0} ^:private default-fleet-sink-flush-interval-ms
  "Maximum time the fleet HTTP sink holds a partial batch before flushing it
   regardless of size — bounds delivery latency for low event rates. 5s."
  5000)

(def ^{:stratum 0} ^:private default-fleet-sink-timeout-ms
  "HTTP request timeout for a fleet-sink batch POST. 10s — generous for a
   batched upload over a slow link, short enough that a hung endpoint doesn't
   stall the flush loop indefinitely."
  10000)

;;------------------------------------------------------------------------------ Internal helpers
(defn ^{:stratum 0} default-events-dir
  "Return the default events directory (~/.miniforge/events). Public
   so the BD-2b sub-3b archive module and the cleanup pass (sub-3c)
   can resolve the same root the file sink writes under."
  ^java.io.File []
  (io/file (config/miniforge-home) "events"))

(defn- ^{:stratum 0} now-sortable-str
  "Return a sortable UTC timestamp string for use as a file-name prefix.
   Format: yyyyMMdd'T'HHmmss'Z', e.g. 20260411T100000Z."
  []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
           (ZonedDateTime/now ZoneOffset/UTC)))

(def ^{:stratum 0} ^:private instant-write-handler
  "Transit write handler for java.time.Instant — writes the instant's
   RFC-3339 string under tag `t`, surfacing as a `~t...` tag-string in
   verbose Transit JSON (same shape Transit gives java.util.Date in
   verbose mode)."
  (transit/write-handler
   (constantly "t")
   (fn [^Instant v] (str v))))

(defn- ^{:stratum 0} snowflake-uuid?
  "True when `id` is a UUID whose low 64 bits are zero — the shape that
   `snowflake/long->uuid` produces. Random UUIDs collide on this with
   probability ~1 in 2^64, which is negligible."
  [id]
  (and (uuid? id)
       (zero? (.getLeastSignificantBits ^java.util.UUID id))))

;; File Sink paths
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

;; Cleanup
(defn- ^{:stratum 0} walk-tree
  "`dir` and every entry under it, parents before children. Unlike
   `file-seq` it does not descend into a symlinked directory below `dir`
   (`dir` itself may be one), so a link inside the events tree cannot
   lead cleanup to files outside it."
  [^java.io.File dir]
  (let [real-dir? (fn [^java.io.File f]
                    (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f)))))
        children (fn [^java.io.File f] (seq (.listFiles f)))]
    (cons dir (mapcat #(tree-seq real-dir? children %)
                      (when (.isDirectory dir) (children dir))))))

(defn- ^{:stratum 0} delete-all!
  "Delete each of `files` in order; return how many regular files went.
   Directories in `files` are deleted too but not counted, and only go
   once empty, so pass children before their parents."
  [files]
  (reduce (fn [n ^java.io.File f]
            (let [file? (.isFile f)]
              (if (and (.delete f) file?) (inc n) n)))
          0
          files))

(defn- ^{:stratum 0} child-dirs
  "The directories directly inside `dir`, symlinks excluded; empty when
   `dir` does not exist."
  [^java.io.File dir]
  (filter (fn [^java.io.File f]
            (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f)))))
          (.listFiles dir)))

(defn- ^{:stratum 0} archive-expired?
  "True when the archived workflow directory `dir` finished archiving
   before `cutoff` (epoch ms). The archive time is the mtime of its
   `archived.marker`: the archive creates the marker once, as its last
   step, and nothing writes it afterwards. A directory without the
   marker is a half-finished archive that recovery still has to
   complete, so it never counts as expired."
  [^java.io.File dir cutoff]
  (let [marker (io/file dir (layout/archived-marker-filename))]
    (and (.isFile marker) (< (.lastModified marker) cutoff))))

(defmulti ^{:stratum 0} retention-hold
  "Whether a store outside the event stream still needs a workflow's
   stored events past their TTL. Dispatches on a hold key; each owner
   registers one method under its own key (the workflow checkpoint store
   holds a run's events while its checkpoint exists, so a resume does not
   find a checkpoint and no events). `cleanup-stale-events!` keeps an
   expired archived or legacy workflow directory when any registered hold
   answers true for its directory name. Args: hold key, workflow id
   (the directory name, a string), and the events root being cleaned (so
   a hold can read the workflow's own events)."
  (fn [hold _workflow-id _events-dir] hold))

(defmethod ^{:stratum 0} retention-hold :default [_hold _workflow-id _events-dir] false)

(defn- ^{:stratum 0} legacy-workflow-expired?
  "True when `dir`, a directory directly under the events root outside
   the live, archived and operator trees, is a pre-archive flat workflow
   directory nobody has written since `cutoff` (epoch ms): a visible
   name; nothing inside but event and temp files (no subdirectory, no
   manifest, nothing another tool keeps there); and the directory and
   every file in it last modified before `cutoff`. The file sink no
   longer writes this layout, so such a directory is a finished or
   abandoned run, and it goes whole or not at all."
  [^java.io.File dir cutoff manifest-name]
  (let [children (.listFiles dir)]
    (and (some? children)
         (not (str/starts-with? (.getName dir) "."))
         (< (.lastModified dir) cutoff)
         (every? (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (and (.isFile f)
                          (not= manifest-name n)
                          (or (str/ends-with? n ".json")
                              (str/ends-with? n ".transit.tmp"))
                          (< (.lastModified f) cutoff))))
                 children))))

(defn- ^{:stratum 0} no-events?
  "True when workflow directory `dir` holds no event file."
  [^java.io.File dir]
  (not-any? #(str/ends-with? (.getName ^java.io.File %) ".json") (.listFiles dir)))

(defn- ^{:stratum 0} idle-unmanaged-run?
  "True when live workflow directory `dir` has a visible name, no
   manifest (no JVM runner manages it), nothing but event and temp files
   besides an `archived.marker` an interrupted archive left (a regular
   file: a symlink or a directory of that name makes the run ineligible),
   and neither it nor any event file was modified since `cutoff`."
  [^java.io.File dir cutoff manifest-name marker-name]
  (let [children (.listFiles dir)]
    (and (some? children)
         (not (str/starts-with? (.getName dir) "."))
         (< (.lastModified dir) cutoff)
         (every? (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (or (and (= marker-name n)
                              (Files/isRegularFile (.toPath f)
                                                   (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                         (and (.isFile f)
                              (not= manifest-name n)
                              (or (str/ends-with? n ".json") (str/ends-with? n ".transit.tmp"))
                              (< (.lastModified f) cutoff)))))
                 children))))

(defn- ^{:stratum 0} run-verdict
  ":archive when the newest workflow lifecycle event in live run `dir` is
   a successful :workflow/completed, the newest by event timestamp then
   sequence number across all of its event files; :keep when it is not
   (a started, resumed, failed or cancelled run: resume can still write
   to it). When any file in `dir` is a symlink, or an event file cannot
   be read or decoded, a string saying which: the run stays, since the
   event it cannot see could be a later start. File mtimes play no part
   here; only the idle check reads them. Reads the transit-JSON this
   namespace writes."
  [^java.io.File dir]
  (let [children (.listFiles dir)
        parse (fn [^java.io.File f]
                (try+ (json/parse-string (slurp f :encoding "UTF-8"))
                      (catch Exception _ nil)))
        order (fn [e] [(some-> (get e "~:event/timestamp") (subs 2) Instant/parse .toEpochMilli)
                       (get e "~:event/sequence-number" 0)])
        lifecycle #{"~:workflow/started" "~:workflow/resumed" "~:workflow/completed"
                    "~:workflow/failed" "~:workflow/cancelled"}
        symlink (some #(when (Files/isSymbolicLink (.toPath ^java.io.File %)) %) children)
        events  (when-not symlink
                  (->> children
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".json"))
                       (map (juxt identity parse))))
        bad     (some (fn [[f e]] (when-not (map? e) f)) events)
        newest  (->> (map second events)
                     (filter #(lifecycle (get % "~:event/type")))
                     (sort-by order #(compare %2 %1))
                     first)]
    (cond
      symlink (str "symlinked file " (.getName ^java.io.File symlink))
      bad     (str "unreadable or undecodable event file " (.getName ^java.io.File bad))
      (and (= "~:workflow/completed" (get newest "~:event/type"))
           (not (#{"~:failure" "~:failed" "~:cancelled"} (get newest "~:workflow/status"))))
      :archive
      :else :keep)))

(defn- ^{:stratum 0} archive-completed-run!
  "Archive live workflow directory `dir` into `archived-root` as the JVM
   runner does at the end of a run: write its `archived.marker` first,
   then move the directory in one atomic rename, so an archived
   directory always has its marker; an interrupted attempt leaves the
   run in live/ for the next cleanup. Returns 1 when moved, else 0."
  [^java.io.File dir ^java.io.File archived-root marker-name]
  (let [target (io/file archived-root (.getName dir))]
    (if (.exists target)
      0
      (try+
        (.mkdirs archived-root)
        ;; NOFOLLOW_LINKS: a symlinked marker fails here instead of
        ;; truncating whatever it points to outside the events tree.
        (.close (Files/newOutputStream (.toPath (io/file dir marker-name))
                                       (into-array OpenOption [StandardOpenOption/CREATE
                                                               StandardOpenOption/TRUNCATE_EXISTING
                                                               StandardOpenOption/WRITE
                                                               LinkOption/NOFOLLOW_LINKS])))
        (Files/move (.toPath dir) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
        1
        (catch Exception _ 0)))))

;; Stream Sinks
(defn ^{:stratum 0} stdout-sink
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
                       :json (str (json/generate-string event {:pretty (not compact?)}))
                       (pr-str event))]
          (println output)
          (flush))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "WARNING: Event sink write failed: " (ex-message e)))))))))

(defn ^{:stratum 0} stderr-sink
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
                         :json (json/generate-string event)
                         (pr-str event))]
            (binding [*out* *err*]
              (println output)))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "WARNING: Event sink write failed: " (ex-message e))))))))))

;; Multi-Sink
(defn ^{:stratum 0} multi-sink
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

(defn- ^{:stratum 1} workflow-id-segment
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

(defn ^{:stratum 1} cleanup-stale-events!
  "Prune stored events whose retention has run out. What goes depends on
   where it lives under the events directory:

     live/{wid}/      Nothing of a run that has a manifest (the JVM
                      runner's; its archive moves it) or that is still
                      written to: resume replays it. A manifest-less run
                      (a Babashka run, which never archives) idle for
                      the TTL is archived here when its newest lifecycle
                      event is a successful completion (a failed or
                      cancelled run may still be resumed), so the
                      archived/ rules below apply to it next; if it
                      holds no event file, its directory goes. A run
                      with a symlinked, unreadable or undecodable file
                      stays, with one warning per process.
     archived/{wid}/  The whole directory, once its archive is older
                      than the TTL (by its `archived.marker`). One still
                      missing the marker is left for archive recovery.
     operator/        Each event file older than the TTL.
     {wid}/ (legacy)  The whole directory, once it and every file in it
                      are older than the TTL; never one that holds
                      anything but event files.

   An archived or legacy workflow a `retention-hold` keeps stays, however
   old: its checkpoint (say) still points at it.

   Anywhere in the tree, `.transit.tmp` files older than the TTL go too:
   a writer killed between its temp write and the rename leaves one, and
   no reader ever picks it up. No symlink below the events directory is
   followed, a symlinked archived/ or operator/ included; the events
   directory itself may be a symlink.

   Arguments:
     opts - Map with optional:
       :events-dir - Directory to clean (default: ~/.miniforge/events)
       :base-dir   - Alias for :events-dir
       :ttl-ms     - Retention in milliseconds (default: 7 days)

   Returns: Number of files deleted"
  [& [opts]]
  (let [ttl-ms (get opts :ttl-ms default-event-ttl-ms)
        events-dir (io/file (or (:events-dir opts) (:base-dir opts) (default-events-dir)))
        cutoff (- (System/currentTimeMillis) ttl-ms)
        stale? (fn [^java.io.File f suffix]
                 (and (.isFile f)
                      (str/ends-with? (.getName f) suffix)
                      (< (.lastModified f) cutoff)))
        ;; A registered `retention-hold` keeps the workflow; one that
        ;; throws (an ex-info included, whose thrown object try+ sees as
        ;; its data map) keeps it too: an unanswered question deletes
        ;; nothing.
        holds (remove #{:default} (keys (methods retention-hold)))
        nonfatal? (fn [thrown] (not (instance? Error thrown)))
        held? (fn [^java.io.File dir]
                (boolean (some (fn [hold]
                                 (try+ (retention-hold hold (.getName dir) events-dir)
                                       (catch nonfatal? _ true)))
                               holds)))]
    (if (.isDirectory events-dir)
      (let [reserved #{(layout/live-subdir) (layout/archived-subdir) (layout/operator-subdir)}
            ;; A symlinked archived/ or operator/ points outside the
            ;; events tree: never list or delete through it.
            real-subdir (fn [subdir]
                          (let [d (io/file events-dir subdir)]
                            (when-not (Files/isSymbolicLink (.toPath d)) d)))
            archived-root (real-subdir (layout/archived-subdir))
            operator-root (real-subdir (layout/operator-subdir))
            live-root     (real-subdir (layout/live-subdir))
            archives (when archived-root
                       (->> (child-dirs archived-root)
                            (filter #(archive-expired? % cutoff))
                            (remove held?)))
            legacy (->> (child-dirs events-dir)
                        (remove #(contains? reserved (.getName ^java.io.File %)))
                        (filter #(legacy-workflow-expired? % cutoff manifest-filename))
                        (remove held?))
            pruned (reduce + 0 (map #(delete-all! (reverse (walk-tree %)))
                                    (concat archives legacy)))
            operator (if operator-root
                       (->> (walk-tree operator-root)
                            (filter #(stale? % ".json"))
                            delete-all!)
                       0)
            temps (->> (walk-tree events-dir)
                       (filter #(stale? % ".transit.tmp"))
                       delete-all!)
            marker    (layout/archived-marker-filename)
            idle-live (when live-root
                        (filter #(idle-unmanaged-run? % cutoff manifest-filename marker)
                                (child-dirs live-root)))
            emptied (reduce + 0 (map #(delete-all! (reverse (walk-tree %)))
                                     (filter no-events? idle-live)))]
        (when archived-root
          (doseq [dir (remove no-events? idle-live)
                  :let [verdict (run-verdict dir)
                        key     (str dir)]]
            (cond
              (= :archive verdict) (archive-completed-run! dir archived-root marker)
              (and (string? verdict)
                   (not (contains? (first (swap-vals! warned-runs conj key)) key)))
              (binding [*out* *err*]
                (println (str "WARNING: event cleanup keeps " dir " in live/: " verdict))))))
        (+ pruned operator temps emptied))
      0)))

;; Fleet Sink
(defn ^{:stratum 1} fleet-sink
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
        batch-size       (:batch-size opts default-fleet-sink-batch-size)
        flush-interval-ms (:flush-interval-ms opts default-fleet-sink-flush-interval-ms)
        timeout-ms       (:timeout-ms opts default-fleet-sink-timeout-ms)
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

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} new-event-file-path
  "Return a java.io.File for `event` inside `parent-dir`. Creates the
   directory if needed. The filename is derived from the event itself
   (BD-2b filename grammar — see `event-filename`)."
  [parent-dir event]
  (let [dir (io/file parent-dir)]
    (.mkdirs dir)
    (io/file dir (event-filename event))))

(defn ^{:stratum 2} live-workflow-dir
  "Return `{base-dir}/live/{workflow-id}/`. Canonical path for an
   in-flight workflow's event files + manifest."
  (^java.io.File [workflow-id]
   (live-workflow-dir (default-events-dir) workflow-id))
  (^java.io.File [base-dir workflow-id]
   (io/file (live-dir base-dir) (workflow-id-segment workflow-id))))

(defn ^{:stratum 2} archived-workflow-dir
  "Return `{base-dir}/archived/{workflow-id}/`. The destination of
   the BD-2b sub-3b atomic archive operation."
  (^java.io.File [workflow-id]
   (archived-workflow-dir (default-events-dir) workflow-id))
  (^java.io.File [base-dir workflow-id]
   (io/file (archived-dir base-dir) (workflow-id-segment workflow-id))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} workflow-dir
  "Return the per-workflow directory for an in-flight workflow. As of
   BD-2b sub-3b this is an alias for `live-workflow-dir` —
   `{base-dir}/live/{workflow-id}/`. The file sink for event files
   and the manifest module (sub-2) both reference the same path so
   archival's `mv live → archived` rename moves them together."
  (^java.io.File [workflow-id]
   (live-workflow-dir workflow-id))
  (^java.io.File [base-dir workflow-id]
   (live-workflow-dir base-dir workflow-id)))

(defn ^{:stratum 3} event-file-path
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

(defn ^{:stratum 3} operator-event-file-path
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

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} file-sink
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

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  ;; The directory is resolved once, at creation (an explicit nil
  ;; :base-dir included): the background cleanup and every write use the
  ;; one the sink was created for, never one resolved later (a test
  ;; pointing the default at a temp dir while it creates the sink keeps
  ;; the cleanup there too).
  (let [base-dir (or (:base-dir opts) (default-events-dir))]
    ;; Non-blocking cleanup on sink creation
    (future (try (cleanup-stale-events! (update opts :events-dir #(or % base-dir)))
                 (catch Exception _ nil)))
    (fn [event]
      (try
        (let [file-path (if-let [workflow-id (:workflow/id event)]
                          (event-file-path base-dir workflow-id event)
                          (operator-event-file-path base-dir event))
              f   (io/file file-path)
              dir (or (.getParentFile f) (io/file "."))
              _   (.mkdirs dir)
              tmp (java.io.File/createTempFile ".es-" ".transit.tmp" dir)]
          (try
            ;; Explicit UTF-8: payload strings can carry non-ASCII and these
            ;; files are a cross-language contract surface — platform default
            ;; encoding must never decide the on-disk bytes.
            (spit tmp (event->transit-json event) :encoding "UTF-8")
            (Files/move (.toPath tmp) (.toPath f)
                        (into-array StandardCopyOption
                                    [StandardCopyOption/ATOMIC_MOVE
                                     StandardCopyOption/REPLACE_EXISTING]))
            (catch Throwable e
              (try (Files/deleteIfExists (.toPath tmp))
                   (catch Throwable cleanup-e (.addSuppressed e cleanup-e)))
              (throw e))))
        (catch Exception e
          ;; Log to stderr so failures are visible without breaking the event stream
          (binding [*out* *err*]
            (println (str "WARNING: Event sink write failed: " (ex-message e))))
          nil)))))

;------------------------------------------------------------------------------ Layer 5

;; Sink Factory
(defn ^{:stratum 5} create-sink
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
                             {:config sink-config
                              :config/error :invalid-config})))

;------------------------------------------------------------------------------ Layer 6

(defn ^{:stratum 6} create-sinks-from-config
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
