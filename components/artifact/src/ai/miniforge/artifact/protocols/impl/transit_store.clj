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
(ns ai.miniforge.artifact.protocols.impl.transit-store
  "Implementation functions for TransitArtifactStore protocol.

   These functions implement the ArtifactStore protocol for the Transit-based store.
   They are used by the TransitArtifactStore record.

   Disk writes run as actions on the store's writer agent, one at a time
   and in submission order; close-store waits for them with a bounded
   timeout. Every file is written to a sibling temp file and renamed over
   the target, so a reader, or a process cut off mid-write, never sees a
   partial file. Index writes also hold the index lock (a per-directory
   monitor in this JVM plus an OS file lock across processes) and merge
   with the index already on disk, so concurrent stores and processes do
   not drop each other's entries.

   Plain `try` throughout: the artifact component has no slingshot
   dependency (rule 211, exemption 1)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.artifact.messages :as messages]
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [cognitect.transit :as transit]
   [ai.miniforge.artifact.core :as core]
   [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
   [ai.miniforge.logging.interface :as log])
  (:import
   [java.nio.channels FileChannel]
   [java.nio.file CopyOption Files OpenOption StandardCopyOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-close-timeout-ms
  "Upper bound close-store waits for queued disk writes, so a stuck write
   (e.g. another process holding the index lock) cannot hang CLI exit."
  10000)

(defonce ^{:stratum 0 :private true} index-monitors
  ;; Canonical artifacts-dir path -> monitor object. The OS file lock
  ;; serializes processes, but one JVM may hold only one lock per file:
  ;; a second store on the same directory would get
  ;; OverlappingFileLockException instead of waiting.
  (atom {}))

;; Pure functions for path handling
(defn ^{:stratum 0} artifacts-dir
  "Get the artifacts directory path.
   Defaults to ~/.miniforge/artifacts"
  ([] (artifacts-dir nil))
  ([base-dir]
   (if base-dir
     (str base-dir "/artifacts")
     (str (config/miniforge-home) "/artifacts"))))

(defn ^{:stratum 0} artifact-file-path
  "Get the file path for an artifact by ID."
  [artifacts-dir artifact-id]
  (str artifacts-dir "/" artifact-id ".transit.json"))

(defn ^{:stratum 0} index-file-path
  "Get the path to the metadata index file."
  [artifacts-dir]
  (str artifacts-dir "/index.transit.json"))

(defn ^{:stratum 0} lock-file-path
  "Get the path to the file locked while the index is rewritten.
   Separate from the index itself, which is replaced by rename."
  [artifacts-dir]
  (str artifacts-dir "/index.lock"))

(defn ^{:stratum 0} ensure-artifacts-dir!
  "Ensure the artifacts directory exists."
  [dir]
  (.mkdirs (io/file dir))
  dir)

;; Transit serialization
(defn ^{:stratum 0} write-transit
  "Write data to a file in Transit JSON format.
   Writes a sibling temp file, then renames it over the target, so the
   file holds either its old content or the new content, never a
   truncated mix."
  [file-path data]
  (let [target (io/file file-path)
        tmp (io/file (.getParentFile target)
                     (str (.getName target) "." (random-uuid) ".tmp"))]
    (try
      (with-open [out (io/output-stream tmp)]
        (transit/write (transit/writer out :json) data))
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      (finally
        ;; Gone after a successful move; on failure, don't leave a
        ;; uniquely named orphan behind for every attempt.
        (Files/deleteIfExists (.toPath tmp))))))

(defn ^{:stratum 0} read-transit
  "Read data from a Transit JSON file.
   Returns nil if file doesn't exist."
  [file-path]
  (when (.exists (io/file file-path))
    (with-open [in (io/input-stream file-path)]
      (let [reader (transit/reader in :json)]
        (transit/read reader)))))

(defn ^{:stratum 0} add-to-index
  "Add artifact metadata to the index."
  [index artifact]
  (let [id (:artifact/id artifact)
        metadata {:artifact/id id
                  :artifact/type (:artifact/type artifact)
                  :artifact/version (:artifact/version artifact)
                  :artifact/parents (:artifact/parents artifact)
                  :artifact/children (:artifact/children artifact)}]
    (assoc index id metadata)))

(defn ^{:stratum 0} update-index-links
  "Update parent-child links in the index."
  [index parent-id child-id]
  (-> index
      (update-in [parent-id :artifact/children] (fnil conj []) child-id)
      (update-in [child-id :artifact/parents] (fnil conj []) parent-id)))

(defn- ^{:stratum 0} carry-links
  "Return `entry` (an artifact or an index entry) with the parents and
   children of `previous` kept in front of its own. Links are
   append-only: link-artifacts or a save that lists them adds them, and
   no later save removes them. Every other field comes from `entry`."
  [previous entry]
  (reduce (fn [acc k]
            (let [links (distinct (concat (get previous k) (get acc k)))]
              (if (seq links) (assoc acc k (vec links)) acc)))
          entry
          [:artifact/parents :artifact/children]))

(defn- ^{:stratum 0} drop-written
  "Remove from `changed` (id -> change count) the ids whose count is
   still the one `written` recorded, i.e. not changed since that write."
  [changed written]
  (reduce-kv (fn [m id n] (if (= n (get m id)) (dissoc m id) m)) changed written))

;; Protocol implementations
(defn ^{:stratum 0} log-artifact-saved
  "Log artifact save operation."
  [logger artifact-id artifact]
  (when logger
    (log/debug logger :system :artifact/saved
               {:data {:artifact-id artifact-id
                       :artifact-type (:artifact/type artifact)
                       :storage :transit}})))

(defn ^{:stratum 0} log-artifact-save-failed
  "Log artifact save failure."
  [logger artifact-id e]
  (when logger
    (log/error logger :system :artifact/save-failed
               {:message (.getMessage e)
                :data {:artifact-id artifact-id}})))

(defn- ^{:stratum 0} criterion-match?
  [metadata [k v]]
  (= (get metadata k) v))

(defn- ^{:stratum 0} load-indexed-artifact
  [record id]
  (p/load-artifact record id))

(defn ^{:stratum 0} update-cache-links
  "Update cached artifacts with new links."
  [cache parent-id child-id]
  (when-let [parent (get @cache parent-id)]
    (swap! cache assoc parent-id (core/add-child parent child-id)))
  (when-let [child (get @cache child-id)]
    (swap! cache assoc child-id (core/add-parent child parent-id))))

;; Anomaly-returning helpers
(defn- ^{:stratum 0} anomaly?
  [x]
  (anomaly/anomaly? x))

(defn- ^{:stratum 0} link-target-message
  [role]
  (case role
    :parent (messages/t :link/parent-not-found)
    :child  (messages/t :link/child-not-found)
    (messages/t :link/artifact-not-found)))

(defn- ^{:stratum 0} link-target-role
  [result]
  (get-in result [:anomaly/data :artifact/role]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-index-lock
  "Call f while holding the index lock for artifacts-dir and return its
   result. Closing the channel releases the OS lock (FileLock's own
   methods are not callable under babashka)."
  [artifacts-dir f]
  (let [dir-key (.getCanonicalPath (io/file artifacts-dir))
        monitor (-> (swap! index-monitors
                           (fn [m] (if (contains? m dir-key) m (assoc m dir-key (Object.)))))
                    (get dir-key))]
    (locking monitor
      (with-open [channel (FileChannel/open (.toPath (io/file (lock-file-path artifacts-dir)))
                                            (into-array OpenOption [StandardOpenOption/CREATE
                                                                    StandardOpenOption/WRITE]))]
        (.lock channel)
        (f)))))

;; Index management
(defn ^{:stratum 1} load-index
  "Load the artifact metadata index.
   Returns empty map if index doesn't exist."
  [artifacts-dir]
  (or (read-transit (index-file-path artifacts-dir))
      {}))

(defn ^{:stratum 1} merge-indexes
  "Merge the in-memory index into the on-disk one. Ids this store changed
   since its last index write take the in-memory entry with the on-disk
   links carried forward; other ids keep the on-disk entry, or the
   in-memory one when the disk has none."
  [disk-index memory-index changed-ids]
  (reduce-kv (fn [merged id entry]
               (cond (contains? changed-ids id) (assoc merged id (carry-links (get merged id) entry))
                     (contains? merged id) merged
                     :else (assoc merged id entry)))
             disk-index
             memory-index))

(defn ^{:stratum 1} load-artifact-from-disk
  "Load an artifact from disk.
   Returns nil if not found."
  [artifacts-dir artifact-id]
  (let [file-path (artifact-file-path artifacts-dir artifact-id)]
    (read-transit file-path)))

(defn- ^{:stratum 1} metadata-match?
  [criteria metadata]
  (every? (partial criterion-match? metadata) criteria))

(defn- ^{:stratum 1} link-target-anomaly
  [artifact-id role]
  (anomaly/anomaly :not-found
                   (link-target-message role)
                   {:artifact/id artifact-id
                    :artifact/role role}))

(defn ^{:stratum 1} close-store
  "Close store implementation. Waits up to the store's close timeout
   for queued disk writes; returns true when they all finished, false
   (and logs a warning) when the timeout ran out first."
  [{:keys [cache logger writer close-timeout-ms]}]
  (let [timeout-ms (or close-timeout-ms default-close-timeout-ms)
        flushed? (if writer (await-for timeout-ms writer) true)]
    (if flushed?
      (log/info logger :system :artifact/store-closed
                {:data {:artifact-count (count @cache)}})
      (log/warn logger :system :artifact/store-close-timeout
                {:message (messages/t :store/close-timeout)
                 :data {:timeout-ms timeout-ms}}))
    flushed?))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} persist-artifact!
  "Persist an artifact to disk in Transit format. Under the index lock,
   links already in the file on disk are carried forward (carry-links):
   another store may have linked this artifact since this one cached it."
  [artifacts-dir artifact]
  (let [id (:artifact/id artifact)
        file-path (artifact-file-path artifacts-dir id)]
    (call-with-index-lock
     artifacts-dir
     (fn []
       (let [on-disk (try (read-transit file-path) (catch Exception _ nil))]
         (write-transit file-path (carry-links (when (map? on-disk) on-disk) artifact)))))
    id))

(defn ^{:stratum 2} persist-index-action
  "Writer action: under the index lock, merge the in-memory index with
   the one on disk (other stores and processes write it too; see
   merge-indexes), keep the result in memory and write it. `changed`
   (id -> change count) names the ids this store changed; written ones
   are dropped from it. The writer's state is the index last
   written; if memory still holds it, an earlier queued write already
   covered this one and it is skipped. Failures are logged and leave the
   state alone, so the next queued write retries."
  [last-written artifacts-dir index changed logger]
  (if (identical? last-written @index)
    last-written
    (try
      (call-with-index-lock
       artifacts-dir
       (fn []
         (let [disk-index (load-index artifacts-dir)
               seen (volatile! nil)
               merged (swap! index (fn [memory]
                                     (merge-indexes disk-index memory
                                                    (set (keys (vreset! seen @changed))))))]
           (write-transit (index-file-path artifacts-dir) merged)
           (swap! changed drop-written @seen)
           merged)))
      (catch Exception e
        (log/error logger :system :artifact/index-save-failed
                   {:message (.getMessage e)
                    :data {:artifacts-dir artifacts-dir}})
        last-written))))

(defn ^{:stratum 2} load-artifact-impl
  "Load artifact implementation."
  [{:keys [artifacts-dir cache logger]} id]
  ;; Try memory cache first
  (or (get @cache id)
      ;; Fall back to loading from disk
      (when-let [artifact (load-artifact-from-disk artifacts-dir id)]
        ;; Cache in memory
        (swap! cache assoc id artifact)
        (when logger
          (log/debug logger :system :artifact/loaded
                     {:data {:artifact-id id
                             :source :disk}}))
        artifact)))

(defn- ^{:stratum 2} matching-index-id
  [criteria [id metadata]]
  (when (metadata-match? criteria metadata)
    id))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} persist-artifact-action
  "Writer action: persist one artifact file, logging the outcome."
  [state artifacts-dir artifact logger]
  (let [id (:artifact/id artifact)]
    (try
      (persist-artifact! artifacts-dir artifact)
      (log-artifact-saved logger id artifact)
      (catch Exception e
        (log-artifact-save-failed logger id e))))
  state)

(defn ^{:stratum 3} persist-linked-artifacts
  "Writer action: re-persist both linked artifacts to disk."
  [state record artifacts-dir parent-id child-id logger]
  (try
    (when-let [parent (p/load-artifact record parent-id)]
      (persist-artifact! artifacts-dir parent))
    (when-let [child (p/load-artifact record child-id)]
      (persist-artifact! artifacts-dir child))
    (catch Exception e
      (when logger
        (log/error logger :system :artifact/link-persist-failed
                   {:message (.getMessage e)
                    :data {:parent-id parent-id
                           :child-id child-id}}))))
  state)

(defn ^{:stratum 3} filter-by-criteria
  "Filter artifacts by criteria."
  [index criteria]
  (keep (partial matching-index-id criteria) index))

(defn ^{:stratum 3} find-link-target
  "Load an artifact for linking, or return a typed not-found anomaly."
  [record artifact-id role]
  (if-let [artifact (load-artifact-impl record artifact-id)]
    artifact
    (link-target-anomaly artifact-id role)))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} save-artifact
  "Save artifact implementation. Under the writer's monitor (so queue
   order matches update order when a link or another save runs at the
   same time): carries the cached and indexed links into the artifact
   (carry-links), marks it changed, updates cache and index, and queues
   the file and index writes.
   An index entry whose file never landed loads as nil; query skips it."
  [{:keys [artifacts-dir cache index logger writer changed]} saved]
  (let [id (:artifact/id saved)]
    (locking writer
      (let [artifact (carry-links (get @index id) (carry-links (get @cache id) saved))]
        (swap! changed update id (fnil inc 0))
        (swap! cache assoc id artifact)
        (swap! index add-to-index artifact)
        (send-off writer persist-artifact-action artifacts-dir artifact logger)
        (send-off writer persist-index-action artifacts-dir index changed logger)))
    id))

(defn ^{:stratum 4} query-artifacts
  "Query artifacts implementation."
  [{:keys [index] :as record} criteria]
  (if (empty? criteria)
    ;; Return all artifacts
    (let [all-ids (keys @index)]
      (keep (partial load-indexed-artifact record) all-ids))
    ;; Filter by criteria using index first for efficiency
    (let [matching-ids (filter-by-criteria @index criteria)
          artifacts (keep (partial load-indexed-artifact record) matching-ids)]
      (vec artifacts))))

(defn ^{:stratum 4} link-artifacts
  "Link artifacts, preserving the ArtifactStore boolean facade.
   Missing link targets are represented as anomalies internally and logged."
  [{:keys [artifacts-dir cache index logger writer changed] :as record} parent-id child-id]
  (let [parent-result (find-link-target record parent-id :parent)
        child-result  (find-link-target record child-id  :child)]
    (if (or (anomaly? parent-result) (anomaly? child-result))
      (do
        (doseq [result [parent-result child-result]
                :when  (anomaly? result)]
          (when logger
            (log/error logger :system :artifact/link-failed
                       {:message (:anomaly/message result)
                        :data    {:parent-id parent-id
                                  :child-id  child-id
                                  :role      (link-target-role result)}})))
        false)
      (do
        ;; Under the writer's monitor, as in save-artifact
        (locking writer
          (swap! changed #(-> % (update parent-id (fnil inc 0)) (update child-id (fnil inc 0))))
          (swap! index update-index-links parent-id child-id)
          (update-cache-links cache parent-id child-id)
          ;; Re-persist both artifacts, then the index, on the writer
          (send-off writer persist-linked-artifacts record artifacts-dir parent-id child-id logger)
          (send-off writer persist-index-action artifacts-dir index changed logger))

        (when logger
          (log/debug logger :system :artifact/linked
                     {:data {:parent-id parent-id
                             :child-id  child-id}}))
        true))))
