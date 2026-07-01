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

(ns ai.miniforge.pr-lifecycle.listener-registry
  "Persistent registry of agents waiting on PR merges (N13 §2.7,
   `specs/informative/n13-listener-registry.md`).

   Each listener entry binds a (PR URL, agent) pair so the Resume
   Signal Dispatcher (separate component, follow-up PR) can deliver
   a structured resume primer to the right agent on
   `pull_request.closed.merged`.

   This namespace ships the persistence primitive only. v0 stores
   the current-state snapshot in
   `<repo>/.miniforge/listener-registry.edn` with write-rename
   atomicity. The append-only event log called for in the spec
   (§Lifecycle) is a v1 hardening; the schema already supports
   that shape via per-entry `:status`, so v1 will switch the
   storage backend without changing the public surface.

   Layer 0: schema + validation
   Layer 1: pure state operations
   Layer 2: persistence (read / write-rename)
   Layer 3: lifecycle entry points (register! / unregister! /
            mark-dispatched! / mark-cancelled-on-pr-close! /
            sweep-expired!)"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration (loaded from per-component EDN at namespace-load)

(def ^:private config
  "Component config loaded from `resources/config/listener-registry/defaults.edn`.
   Keeps constants (storage path, valid enums, version, TTL) out of
   source so operators can tune without recompiling."
  (-> (io/resource "config/listener-registry/defaults.edn")
      slurp
      edn/read-string))

(def registry-version
  "On-disk artifact format version. Tracks the spec document version."
  (:registry/version config))

(def default-storage-path
  "Default path under the worktree where the registry artifact lives."
  (:default-storage-path config))

(def default-ttl-seconds
  "Default `:ttl-seconds` per spec §Lifecycle."
  (:default-ttl-seconds config))

(def valid-runtimes        (:valid-runtimes config))
(def valid-channel-kinds   (:valid-channel-kinds config))
(def valid-statuses        (:valid-statuses config))

(def valid-registered-by
  "The three canonical registration moments per spec §Registration moments.
   Registration with any other `:registered-by` MUST be rejected."
  (:valid-registered-by config))

(def ChannelKindEnum  (into [:enum] valid-channel-kinds))
(def RuntimeEnum      (into [:enum] valid-runtimes))
(def StatusEnum       (into [:enum] valid-statuses))
(def RegisteredByEnum (into [:enum] valid-registered-by))

(def ResumeChannel
  [:map
   [:channel/kind   ChannelKindEnum]
   [:channel/target :string]
   [:channel/auth   {:optional true} [:maybe :map]]])

(def ListenerEntry
  [:map
   [:listener/id          uuid?]
   [:pr/url               :string]
   [:pr/repo-id           :string]
   [:pr/number            pos-int?]
   [:agent/id             :string]
   [:session/id           {:optional true} [:maybe :string]]
   [:runtime              RuntimeEnum]
   [:resume-channel       ResumeChannel]
   [:registered-at        inst?]
   [:registered-by        RegisteredByEnum]
   [:ttl-seconds          {:optional true} pos-int?]
   [:status               StatusEnum]
   [:resume/dispatched-at {:optional true} [:maybe inst?]]
   [:resume/dispatch-id   {:optional true} [:maybe uuid?]]
   [:notes                {:optional true} [:maybe :string]]])

(def Registry
  [:map
   [:registry/version      :string]
   [:registry/last-updated inst?]
   [:registry/listeners    [:map-of :string [:vector ListenerEntry]]]])

(defn validate-entry
  "Return a DAG result for `entry` validation. Used by `register!`."
  [entry]
  (if (m/validate ListenerEntry entry)
    (dag/ok entry)
    (dag/err :listener-registry/invalid-entry
             "invalid listener entry"
             {:errors (m/explain ListenerEntry entry)
              :anomaly :listener-registry/invalid-entry})))

;------------------------------------------------------------------------------ Layer 1
;; Pure state operations

(def empty-registry
  {:registry/version      registry-version
   :registry/last-updated #inst "1970-01-01T00:00:00.000-00:00"
   :registry/listeners    {}})

(defn- now-inst [] (java.util.Date.))

(defn- with-touched-timestamp
  [registry]
  (assoc registry :registry/last-updated (now-inst)))

(defn add-entry
  "Pure: append `entry` to the registry under its `:pr/url` key."
  [registry entry]
  (-> registry
      (update-in [:registry/listeners (:pr/url entry)] (fnil conj []) entry)
      with-touched-timestamp))

(defn entries-for-pr
  "Return all entries (any status) for `pr-url`, or empty vector."
  [registry pr-url]
  (get-in registry [:registry/listeners pr-url] []))

(defn active-entries-for-pr
  "Return the subset of entries-for-pr with `:status :active`."
  [registry pr-url]
  (filterv #(= :active (:status %)) (entries-for-pr registry pr-url)))

(defn entries-for-agent
  "Return all entries (any status) bound to `agent-id` across all PRs."
  [registry agent-id]
  (into []
        (comp (mapcat val)
              (filter #(= agent-id (:agent/id %))))
        (:registry/listeners registry)))

(defn update-entry-status
  "Pure: find the entry matching `listener-id` (within the listeners
   for `pr-url`) and apply `update-fn` to it. Returns the updated
   registry (with bumped timestamp). When the entry is not found,
   returns the registry unchanged.

   `update-fn` receives the entry and returns its replacement."
  [registry pr-url listener-id update-fn]
  (let [path  [:registry/listeners pr-url]
        bucket (get-in registry path [])
        idx   (first (keep-indexed
                      (fn [i e]
                        (when (= listener-id (:listener/id e)) i))
                      bucket))]
    (if idx
      (-> registry
          (update-in path
                     (fn [b] (update b idx update-fn)))
          with-touched-timestamp)
      registry)))

(defn auto-expirable?
  "Pure: true when `entry` is `:active`, has `:ttl-seconds`, and the
   wall-clock cutoff (`registered-at + ttl-seconds`) is in the past
   relative to `now`."
  [entry now]
  (and (= :active (:status entry))
       (let [ttl (or (:ttl-seconds entry) default-ttl-seconds)
             reg-at (.getTime ^java.util.Date (:registered-at entry))
             cutoff (+ reg-at (* 1000 ttl))]
         (<= cutoff (.getTime ^java.util.Date now)))))

;; ── Entry construction (Layer 1; consumes only Layer 0/1) ───────────

(defn- new-listener-id [] (random-uuid))

(defn- build-entry
  "Pure: assemble a fully-shaped `ListenerEntry` from `params`,
   stamping a fresh `:listener/id`, `:registered-at`, default
   `:ttl-seconds`, and `:status :active`."
  [params]
  {:listener/id     (new-listener-id)
   :pr/url          (:pr/url params)
   :pr/repo-id      (:pr/repo-id params)
   :pr/number       (:pr/number params)
   :agent/id        (:agent/id params)
   :session/id      (:session/id params)
   :runtime         (:runtime params)
   :resume-channel  (:resume-channel params)
   :registered-at   (now-inst)
   :registered-by   (:registered-by params)
   :ttl-seconds     (or (:ttl-seconds params) default-ttl-seconds)
   :status          :active
   :notes           (:notes params)})

;; ── Per-entry status updates (pure functions over one entry) ────────

(defn- mark-cancelled
  "Pure entry update: any → :cancelled. Caller is responsible for
   sourcing only `:active` entries via `check-active-entry` /
   `active-entries-for-pr`."
  [entry]
  (assoc entry :status :cancelled))

(defn- mark-dispatched
  "Pure entry-update factory: returns a fn that stamps `:dispatched`
   + `:resume/dispatched-at` + `:resume/dispatch-id` on an entry.
   Closes over `dispatch-id` to avoid an inline anonymous fn at the
   call site."
  [dispatch-id]
  (fn [entry]
    (assoc entry
           :status :dispatched
           :resume/dispatched-at (now-inst)
           :resume/dispatch-id dispatch-id)))

(defn- mark-expired
  "Pure entry update: any → :expired."
  [entry]
  (assoc entry :status :expired))

;; ── Result + error builders (pure DAG-result construction) ──────────

(defn- not-found-error
  [pr-url listener-id]
  (dag/err :listener-registry/listener-not-found
           (str "no listener entry " listener-id " for " pr-url)
           {:pr-url pr-url :listener-id listener-id}))

(defn- wrong-status-error
  [pr-url listener-id current-status]
  (dag/err :listener-registry/wrong-status
           (str "listener " listener-id " on " pr-url
                " is " current-status ", expected :active")
           {:pr-url          pr-url
            :listener-id     listener-id
            :current-status  current-status
            :expected-status :active}))

(defn- registration-success
  "Build the success payload returned to the caller of `register!`."
  [entry write-result]
  (dag/ok {:listener-id (:listener/id entry)
           :path        (:path (:data write-result))}))

;; ── Lookup + active-entry assertion (pure registry queries) ─────────

(defn- find-listener
  "Pure: locate the entry matching `listener-id` within the bucket
   for `pr-url`. Returns the entry or nil."
  [registry pr-url listener-id]
  (first (filter #(= listener-id (:listener/id %))
                 (entries-for-pr registry pr-url))))

(defn- check-active-entry
  "Return `(dag/ok entry)` when the listener exists in `registry`
   under `pr-url` AND is currently `:active`. Otherwise a typed
   error per the two failure modes."
  [registry pr-url listener-id]
  (let [entry (find-listener registry pr-url listener-id)]
    (cond
      (nil? entry)                    (not-found-error pr-url listener-id)
      (not= :active (:status entry))  (wrong-status-error pr-url listener-id (:status entry))
      :else                           (dag/ok entry))))

;; ── Bulk-operation helpers (pure reducers + transducer steps) ───────

(defn- apply-cancel-to
  "Reduce step: cancel one `entry` in-place under its known `pr-url`.
   Closure-free — `pr-url` arrives via the entry's bucket lookup."
  [pr-url registry entry]
  (update-entry-status registry pr-url (:listener/id entry) mark-cancelled))

(defn- apply-expire-to
  "Reduce step: expire one [pr-url entry] pair in `registry`."
  [registry [pr-url entry]]
  (update-entry-status registry pr-url (:listener/id entry) mark-expired))

(defn- pair-with-pr-url
  "Pure: turn one `[pr-url entries]` map-entry into a seq of
   `[pr-url entry]` pairs."
  [[pr-url entries]]
  (map (fn [e] [pr-url e]) entries))

(defn- pair-expirable?
  "Pure: predicate for [pr-url entry] pairs against a fixed `now`.
   Returns a function so `filter` doesn't need a closure-bearing
   anonymous fn at the call site."
  [now]
  (fn [[_ entry]] (auto-expirable? entry now)))

(defn- collect-expirable-pairs
  "Pure: walk `registry`'s listener buckets and return a vector of
   `[pr-url entry]` pairs whose entries are auto-expirable as of
   `now`."
  [registry now]
  (into []
        (comp (mapcat pair-with-pr-url)
              (filter (pair-expirable? now)))
        (:registry/listeners registry)))

;------------------------------------------------------------------------------ Layer 2
;; Persistence (read + write-rename)

(defn- storage-path
  [worktree-path]
  (str (fs/path (str worktree-path) default-storage-path)))

(defn- trailing-forms-error
  [path trailing]
  (dag/err :listener-registry/read-failed
           (str "registry file " path " has trailing forms after first value")
           {:path path
            :trailing trailing}))

(defn- read-single-edn-form
  "Read exactly one EDN form from `raw`, asserting EOF after.
   Returns a DAG result containing the parsed value. Parse failures
   inside `edn/read` still bubble to `read-registry`'s I/O boundary;
   trailing forms are expected data corruption and return
   `:listener-registry/read-failed` directly.

   Using PushbackReader + double-read with `:eof` sentinel is the
   only way to detect trailing garbage without parsing the whole
   string first. `edn/read-string` reads the first form and silently
   discards the rest, so a corrupted file like `\"{:a 1} extra junk\"`
   would otherwise round-trip as `{:a 1}`."
  [path raw]
  (let [eof-sentinel ::eof
        rdr (java.io.PushbackReader. (java.io.StringReader. ^String raw))
        first-form (edn/read {:eof eof-sentinel} rdr)
        next-form  (edn/read {:eof eof-sentinel} rdr)]
    (if (identical? eof-sentinel next-form)
      (dag/ok first-form)
      (trailing-forms-error path next-form))))

(defn- schema-error
  "Build a typed `:listener-registry/read-failed` for a registry value
   that parsed but doesn't match the `Registry` malli schema."
  [path parsed]
  (dag/err :listener-registry/read-failed
           (str "registry file " path " failed Registry schema validation")
           {:path    path
            :explain (m/explain Registry parsed)}))

(defn- decode-registry-blob
  "Pure: parse and validate `raw`. Treats blank as `empty-registry`.
   Returns DAG result. Splits the work out of `read-registry` so the
   stage check (file exists, content non-blank, parses, schema-valid)
   doesn't need the cond-inside-cond-inside-cond shape."
  [path raw]
  (if (str/blank? raw)
    (dag/ok empty-registry)
    (let [parse-r (read-single-edn-form path raw)]
      (if-not (dag/ok? parse-r)
        parse-r
        (let [parsed (:data parse-r)]
          (if (m/validate Registry parsed)
            (dag/ok parsed)
            (schema-error path parsed)))))))

(defn- read-failure
  "Build a typed `:listener-registry/read-failed` for a slurp /
   parse / I/O exception. Surfaces ex-data when present."
  [path ^Throwable e]
  (dag/err :listener-registry/read-failed
           (str "failed to read " path ": " (.getMessage e))
           (cond-> {:path path}
             (ex-data e) (assoc :ex-data (ex-data e)))))

(defn read-registry
  "Read the registry from `worktree-path`'s `.miniforge/listener-registry.edn`.
   Returns `(dag/ok <registry>)` on success; `(dag/ok empty-registry)`
   on missing or blank file. Returns `(dag/err :listener-registry/read-failed ...)`
   on:
   - parse failure (corrupt EDN)
   - trailing forms after the first value (silent corruption mode)
   - schema mismatch against the malli `Registry` schema (e.g.
     `:registry/listeners` value is a list rather than a vector,
     which would later trip up `update-entry-status`)

   Schema-mismatch errors carry an `:explain` key under `:data` with
   the malli explanation so callers can surface a structured diagnostic."
  [worktree-path]
  (let [path (storage-path worktree-path)]
    (try
      (if (fs/exists? path)
        (decode-registry-blob path (slurp path))
        (dag/ok empty-registry))
      (catch Throwable e
        (read-failure path e)))))

(defn- best-effort-delete!
  "Try to delete `path`; swallow any throwable. Used to keep
   `.miniforge/` clean of leftover `.tmp` files when an atomic
   move fails (e.g., on filesystems that don't support it)."
  [path]
  (try (fs/delete path) (catch Throwable _ nil)))

(defn write-registry!
  "Write `registry` atomically to `worktree-path`'s storage path.
   Strategy: serialize EDN, write to `<path>.tmp`, then `mv` over
   the canonical path. Eliminates partial reads from concurrent
   readers — the rename is atomic on POSIX filesystems.

   Creates `.miniforge/` if it doesn't exist. On move failure
   (e.g. atomic move not supported on the underlying filesystem),
   best-effort-deletes the leftover `.tmp` file so it doesn't
   accumulate under `.miniforge/`."
  [worktree-path registry]
  (let [path     (storage-path worktree-path)
        tmp-path (str path ".tmp")
        parent   (str (fs/parent path))]
    (try
      (when-not (fs/exists? parent) (fs/create-dirs parent))
      (spit tmp-path (pr-str registry))
      (try
        (fs/move tmp-path path {:replace-existing true :atomic-move true})
        (dag/ok {:path path :listener-count
                 (reduce + 0 (map count (vals (:registry/listeners registry))))})
        (catch Throwable move-err
          (best-effort-delete! tmp-path)
          (dag/err :listener-registry/write-failed
                   (str "failed to write " path ": " (.getMessage move-err))
                   {:path path
                    :tmp-path tmp-path
                    :exception-class (some-> move-err class .getName)})))
      (catch Throwable e
        (dag/err :listener-registry/write-failed
                 (str "failed to write " path ": " (.getMessage e))
                 {:path path})))))

;------------------------------------------------------------------------------ Layer 3
;; Lifecycle entry points (orchestrate Layer 1/2 ops; do I/O)

(defn- persist-new-entry!
  "Read → add → write. Internal helper for `register!`. Assumes the
   caller has already validated the entry."
  [worktree-path entry]
  (let [r (read-registry worktree-path)]
    (if-not (dag/ok? r)
      r
      (let [next-reg (add-entry (:data r) entry)
            write-r  (write-registry! worktree-path next-reg)]
        (if (dag/ok? write-r)
          (registration-success entry write-r)
          write-r)))))

(defn register!
  "Persist a new listener entry for `pr-url` against `agent-id`.

   Required keys in `params`:
   - `:pr/url`           — full PR URL
   - `:pr/repo-id`       — '<org>/<repo>'
   - `:pr/number`        — pos int
   - `:agent/id`         — runtime-stable agent id
   - `:runtime`          — one of `valid-runtimes`
   - `:resume-channel`   — `{:channel/kind :channel/target :channel/auth?}`
   - `:registered-by`    — one of `valid-registered-by`. Registration
                           with any other value MUST be rejected
                           (spec §Registration moments).

   Optional keys:
   - `:session/id`       — current agent session uuid string
   - `:ttl-seconds`      — default `default-ttl-seconds`
   - `:notes`            — free-form

   Returns DAG result with `(dag/ok {:listener-id <uuid> :path ...})`
   on success or typed error on schema/storage failure."
  [worktree-path params]
  (let [registered-by (:registered-by params)]
    (if-not (contains? valid-registered-by registered-by)
      (dag/err :listener-registry/invalid-registered-by
               (str ":registered-by must be one of " valid-registered-by)
               {:received registered-by})
      (let [entry      (build-entry params)
            validation (validate-entry entry)]
        (if-not (dag/ok? validation)
          validation
          (persist-new-entry! worktree-path (:data validation)))))))

(defn- transition-from-active!
  "Internal: load → assert entry is `:active` → update → write.

   Returns:
   - `:listener-registry/listener-not-found` when no such entry exists.
   - `:listener-registry/wrong-status` when the entry isn't `:active`
     — guards against overwriting terminal states (`:dispatched`,
     `:expired`, `:cancelled`) per spec §Lifecycle, which models all
     transitions as `:active → X`.
   - `(dag/ok ...)` from `write-registry!` on the happy path.

   `update-fn` receives the entry (guaranteed `:status :active`) and
   returns its replacement."
  [worktree-path pr-url listener-id update-fn]
  (let [r (read-registry worktree-path)]
    (if-not (dag/ok? r)
      r
      (let [registry (:data r)
            check    (check-active-entry registry pr-url listener-id)]
        (if-not (dag/ok? check)
          check
          (write-registry! worktree-path
                           (update-entry-status registry pr-url listener-id update-fn)))))))

(defn unregister!
  "Transition `listener-id` for `pr-url` from `:active` to `:cancelled`.

   Returns typed error when:
   - no such entry exists (`:listener-registry/listener-not-found`)
   - the entry is already in a terminal state
     (`:listener-registry/wrong-status`) — does NOT overwrite
     `:dispatched` / `:expired` / `:cancelled` per spec §Lifecycle."
  [worktree-path pr-url listener-id]
  (transition-from-active! worktree-path pr-url listener-id mark-cancelled))

(defn mark-dispatched!
  "Transition `listener-id` for `pr-url` from `:active` to
   `:dispatched`, recording `:resume/dispatched-at` and
   `:resume/dispatch-id`. Called by the Resume Signal Dispatcher
   after a successful primer delivery.

   Returns `:listener-registry/wrong-status` rather than overwriting
   when the entry is already in a terminal state — prevents
   double-dispatch and accidental resurrection of cancelled/expired
   listeners."
  [worktree-path pr-url listener-id dispatch-id]
  (transition-from-active! worktree-path pr-url listener-id (mark-dispatched dispatch-id)))

(defn mark-cancelled-on-pr-close!
  "Transition every `:active` listener for `pr-url` to `:cancelled`.
   Called when `pull_request.closed` arrives with `merged: false`
   (spec §Deregistration). Returns DAG result with the count of
   transitioned entries."
  [worktree-path pr-url]
  (let [r (read-registry worktree-path)]
    (if-not (dag/ok? r)
      r
      (let [registry (:data r)
            actives  (active-entries-for-pr registry pr-url)
            next-reg (reduce (partial apply-cancel-to pr-url) registry actives)
            write-r  (write-registry! worktree-path next-reg)]
        (if (dag/ok? write-r)
          (dag/ok {:cancelled-count (count actives) :pr-url pr-url})
          write-r)))))

(defn sweep-expired!
  "Transition every `:active` entry whose TTL has elapsed to
   `:expired`. Returns DAG result with the count of transitioned
   entries. Idempotent — re-running yields a count of 0."
  [worktree-path]
  (let [r (read-registry worktree-path)]
    (if-not (dag/ok? r)
      r
      (let [registry (:data r)
            stale    (collect-expirable-pairs registry (now-inst))
            next-reg (reduce apply-expire-to registry stale)
            write-r  (write-registry! worktree-path next-reg)]
        (if (dag/ok? write-r)
          (dag/ok {:expired-count (count stale)})
          write-r)))))
