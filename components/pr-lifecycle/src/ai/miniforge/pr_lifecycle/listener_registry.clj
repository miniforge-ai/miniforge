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
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Schema + constants

(def registry-version
  "On-disk artifact format version. Tracks the spec document version."
  "0.1.0")

(def default-storage-path
  "Default path under the worktree where the registry artifact lives."
  ".miniforge/listener-registry.edn")

(def default-ttl-seconds
  "Default `:ttl-seconds` per spec §Lifecycle. 7 days."
  604800)

(def valid-runtimes
  #{:claude-cli :codex :miniforge :webhook})

(def valid-channel-kinds
  #{:pty :miniforge-ipc :webhook})

(def valid-statuses
  #{:active :dispatched :expired :cancelled})

(def valid-registered-by
  "The three canonical registration moments per spec §Registration moments.
   Registration with any other `:registered-by` MUST be rejected."
  #{:authoring-agent :operator :workflow})

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

(defn validate-entry!
  "Throw on invalid entry; return entry otherwise. Used by `register!`."
  [entry]
  (when-not (m/validate ListenerEntry entry)
    (throw (ex-info "invalid listener entry"
                    {:errors (m/explain ListenerEntry entry)
                     :anomaly :listener-registry/invalid-entry})))
  entry)

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

;------------------------------------------------------------------------------ Layer 2
;; Persistence (read + write-rename)

(defn- storage-path
  [worktree-path]
  (str (fs/path (str worktree-path) default-storage-path)))

(defn- registry-shape?
  "Cheap structural check: registry MUST be a map with the three
   required top-level keys. Catches the common corruption mode where
   `edn/read-string` reads only the first form and silently ignores
   trailing garbage (e.g. `\"foo bar\"` parses to the symbol `foo` and
   never throws)."
  [v]
  (and (map? v)
       (contains? v :registry/version)
       (contains? v :registry/last-updated)
       (contains? v :registry/listeners)
       (map? (:registry/listeners v))))

(defn read-registry
  "Read the registry from `worktree-path`'s `.miniforge/listener-registry.edn`.
   Returns the registry map on success; `empty-registry` on missing
   file. Returns `(dag/err :listener-registry/read-failed ...)` on
   parse failure or when the parsed value doesn't match the registry
   shape."
  [worktree-path]
  (let [path (storage-path worktree-path)]
    (try
      (cond
        (not (fs/exists? path))
        (dag/ok empty-registry)

        :else
        (let [raw (slurp path)]
          (cond
            (str/blank? raw)
            (dag/ok empty-registry)

            :else
            (let [parsed (edn/read-string raw)]
              (if (registry-shape? parsed)
                (dag/ok parsed)
                (dag/err :listener-registry/read-failed
                         (str "registry file " path " did not parse to a registry-shaped map")
                         {:path path}))))))
      (catch Throwable e
        (dag/err :listener-registry/read-failed
                 (str "failed to read " path ": " (.getMessage e))
                 {:path path})))))

(defn write-registry!
  "Write `registry` atomically to `worktree-path`'s storage path.
   Strategy: serialize EDN, write to `<path>.tmp`, then `mv` over
   the canonical path. Eliminates partial reads from concurrent
   readers — the rename is atomic on POSIX filesystems.

   Creates `.miniforge/` if it doesn't exist."
  [worktree-path registry]
  (let [path     (storage-path worktree-path)
        tmp-path (str path ".tmp")
        parent   (str (fs/parent path))]
    (try
      (when-not (fs/exists? parent) (fs/create-dirs parent))
      (spit tmp-path (pr-str registry))
      (fs/move tmp-path path {:replace-existing true :atomic-move true})
      (dag/ok {:path path :listener-count
               (reduce + 0 (map count (vals (:registry/listeners registry))))})
      (catch Throwable e
        (dag/err :listener-registry/write-failed
                 (str "failed to write " path ": " (.getMessage e))
                 {:path path})))))

;------------------------------------------------------------------------------ Layer 3
;; Lifecycle entry points

(defn- new-listener-id [] (random-uuid))

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
    (cond
      (not (contains? valid-registered-by registered-by))
      (dag/err :listener-registry/invalid-registered-by
               (str ":registered-by must be one of " valid-registered-by)
               {:received registered-by})

      :else
      (let [entry {:listener/id     (new-listener-id)
                   :pr/url          (:pr/url params)
                   :pr/repo-id      (:pr/repo-id params)
                   :pr/number       (:pr/number params)
                   :agent/id        (:agent/id params)
                   :session/id      (:session/id params)
                   :runtime         (:runtime params)
                   :resume-channel  (:resume-channel params)
                   :registered-at   (now-inst)
                   :registered-by   registered-by
                   :ttl-seconds     (or (:ttl-seconds params) default-ttl-seconds)
                   :status          :active
                   :notes           (:notes params)}]
        (try
          (validate-entry! entry)
          (let [r (read-registry worktree-path)]
            (if-not (dag/ok? r)
              r
              (let [registry  (:data r)
                    next-reg  (add-entry registry entry)
                    write-r   (write-registry! worktree-path next-reg)]
                (if (dag/ok? write-r)
                  (dag/ok {:listener-id (:listener/id entry)
                           :path (:path (:data write-r))})
                  write-r))))
          (catch clojure.lang.ExceptionInfo e
            (dag/err (or (:anomaly (ex-data e))
                         :listener-registry/invalid-entry)
                     (.getMessage e)
                     (ex-data e))))))))

(defn- transition!
  "Internal: load → update one entry → write. `update-fn` receives
   the entry and returns its replacement (or itself unchanged)."
  [worktree-path pr-url listener-id update-fn err-code-on-missing]
  (let [r (read-registry worktree-path)]
    (if-not (dag/ok? r)
      r
      (let [registry (:data r)
            bucket   (entries-for-pr registry pr-url)
            present? (some #(= listener-id (:listener/id %)) bucket)]
        (if-not present?
          (dag/err err-code-on-missing
                   (str "no listener entry " listener-id " for " pr-url)
                   {:pr-url pr-url :listener-id listener-id})
          (let [next-reg (update-entry-status registry pr-url listener-id update-fn)]
            (write-registry! worktree-path next-reg)))))))

(defn unregister!
  "Transition `listener-id` for `pr-url` to `:cancelled`. No-op (typed
   error) when no such entry exists."
  [worktree-path pr-url listener-id]
  (transition! worktree-path pr-url listener-id
               (fn [e] (assoc e :status :cancelled))
               :listener-registry/listener-not-found))

(defn mark-dispatched!
  "Transition to `:dispatched`, recording `:resume/dispatched-at` and
   `:resume/dispatch-id`. Called by the Resume Signal Dispatcher
   after a successful primer delivery."
  [worktree-path pr-url listener-id dispatch-id]
  (transition! worktree-path pr-url listener-id
               (fn [e]
                 (assoc e
                        :status :dispatched
                        :resume/dispatched-at (now-inst)
                        :resume/dispatch-id dispatch-id))
               :listener-registry/listener-not-found))

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
            next-reg (reduce
                      (fn [reg e]
                        (update-entry-status reg pr-url (:listener/id e)
                                             (fn [e2] (assoc e2 :status :cancelled))))
                      registry
                      actives)
            write-r  (write-registry! worktree-path next-reg)]
        (if (dag/ok? write-r)
          (dag/ok {:cancelled-count (count actives)
                   :pr-url pr-url})
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
            now      (now-inst)
            stale    (into []
                           (comp (mapcat (fn [[pr-url entries]]
                                           (map (fn [e] [pr-url e]) entries)))
                                 (filter (fn [[_ e]] (auto-expirable? e now))))
                           (:registry/listeners registry))
            next-reg (reduce
                      (fn [reg [pr-url e]]
                        (update-entry-status reg pr-url (:listener/id e)
                                             (fn [e2] (assoc e2 :status :expired))))
                      registry
                      stale)
            write-r  (write-registry! worktree-path next-reg)]
        (if (dag/ok? write-r)
          (dag/ok {:expired-count (count stale)})
          write-r)))))
