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

   The per-artifact files are the source of truth; the index is a
   projection of them. save and link update the in-memory cache and index
   at once and queue the change. The store's writer agent applies queued
   changes in one critical section under the index lock (a per-directory
   monitor in this JVM plus an OS file lock across processes): it
   re-reads the artifact files, applies each change, writes the files,
   sets the on-disk index entries of exactly those ids to their
   projection, and writes the index; an unreadable on-disk index is
   first quarantined and rebuilt from the files (quarantine-index!).
   close-store waits for the writer
   with a bounded timeout. Every file is written to a sibling temp file
   and renamed over the target, so no reader sees a partial file.

   Plain `try` throughout: the artifact component has no slingshot
   dependency (rule 211, exemption 1)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.artifact.messages :as messages]
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.transit :as transit]
   [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
   [ai.miniforge.logging.interface :as log])
  (:import
   [java.io File]
   [java.nio.channels FileChannel]
   [java.nio.file CopyOption Files LinkOption OpenOption StandardCopyOption StandardOpenOption]
   [java.nio.file.attribute FileAttribute PosixFilePermissions]))

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

(defn ^{:stratum 0} write-transit
  "Write data to a file in Transit JSON format.
   Writes a sibling temp file, then renames it over the target, so the
   file holds either its old content or the new content, never a
   truncated mix. When the target exists on a POSIX file system, the
   temp file is created owner-only (0600), written, and given the
   target's permissions just before the rename: a private file is never
   replaced by a more readable one, and a read-only target (e.g. 0400)
   does not block the write. New files get the default mode."
  [file-path data]
  (let [target (io/file file-path)
        tmp (io/file (.getParentFile target)
                     (str (.getName target) "." (random-uuid) ".tmp"))
        mode (when (and (.exists target)
                        (contains? (.supportedFileAttributeViews (.getFileSystem (.toPath target))) "posix"))
               (Files/getPosixFilePermissions (.toPath target) (make-array LinkOption 0)))]
    (try
      (Files/createFile (.toPath tmp)
                        (if mode
                          (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                                                      (PosixFilePermissions/fromString "rw-------"))])
                          (make-array FileAttribute 0)))
      (with-open [out (io/output-stream tmp)]
        (transit/write (transit/writer out :json) data))
      (when mode
        (Files/setPosixFilePermissions (.toPath tmp) mode))
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

(defn- ^{:stratum 0} add-link
  "Add `id` to the `k` links of `artifact` unless it is already there."
  [artifact k id]
  (update artifact k (fn [links] (if (some #{id} links) (vec links) (conj (vec links) id)))))

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

(defn ^{:stratum 1} read-index
  "Read the index on disk. Returns {} when there is no index file, or a
   :fault anomaly when the file exists but does not decode to a map
   (content that decodes to nil included)."
  [artifacts-dir]
  (let [path (index-file-path artifacts-dir)]
    (try
      (if-not (.exists (io/file path))
        {}
        (let [data (read-transit path)]
          (if (map? data)
            data
            (anomaly/anomaly :fault (messages/t :index/unreadable)
                             {:path path :value-type (str (type data))}))))
      (catch Exception e
        (anomaly/exception-anomaly :fault (messages/t :index/unreadable) {:path path} e)))))

(defn ^{:stratum 1} rebuild-index
  "Rebuild the metadata index from the per-artifact files in
   artifacts-dir. Returns {:index m :skipped [file-name ...]}. A file
   that cannot be read, or whose :artifact/id does not map back to that
   same file (compared as canonical paths, so an id such as \"../a\"
   cannot claim a.transit.json), is skipped."
  [artifacts-dir]
  (let [index-name (.getName (io/file (index-file-path artifacts-dir)))
        files (->> (.listFiles (io/file artifacts-dir))
                   (filter (fn [^File f]
                             (and (.isFile f)
                                  (str/ends-with? (.getName f) ".transit.json")
                                  (not= index-name (.getName f))))))]
    (reduce
     (fn [acc ^File f]
       (let [artifact (try (read-transit (.getPath f)) (catch Exception _ nil))
             expected (when (and (map? artifact) (some? (:artifact/id artifact)))
                        (.getCanonicalPath (io/file (artifact-file-path artifacts-dir
                                                                        (:artifact/id artifact)))))]
         (if (= expected (.getCanonicalPath f))
           (update acc :index add-to-index artifact)
           (update acc :skipped conj (.getName f)))))
     {:index {} :skipped []}
     files)))

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
   for queued disk writes. Returns true when they all finished, nothing
   is still queued, no file write failed without a later success, and
   every written artifact is indexed; otherwise logs a warning and
   returns false."
  [{:keys [cache logger pending writer close-timeout-ms]}]
  (let [timeout-ms (or close-timeout-ms default-close-timeout-ms)
        flushed? (if writer (await-for timeout-ms writer) true)
        {:keys [unindexed failed]} (when (and writer flushed?) @writer)
        queued (if pending (count @pending) 0)
        incomplete? (boolean (or (seq unindexed) (seq failed) (pos? queued)))]
    (cond
      (not flushed?) (log/warn logger :system :artifact/store-close-timeout
                               {:message (messages/t :store/close-timeout)
                                :data {:timeout-ms timeout-ms}})
      incomplete? (log/warn logger :system :artifact/store-close-incomplete
                            {:message (messages/t :store/close-incomplete)
                             :data {:unindexed (vec unindexed) :failed (vec failed) :queued queued}})
      :else (log/info logger :system :artifact/store-closed
                      {:data {:artifact-count (count @cache)}}))
    (and flushed? (not incomplete?))))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} write-change!
  "Apply one queued change to the artifact files on disk and write them.
   A save keeps the on-disk links and takes every other field from the
   save (carry-links); a link adds each side to the other's file. A
   missing or unreadable file falls back to the queued copy. Returns
   {:written [[queued written] ...] :failed [id ...]}; failures are logged."
  [{:keys [artifacts-dir logger]} [op a b]]
  (let [on-disk (fn [artifact]
                  (or (try (load-artifact-from-disk artifacts-dir (:artifact/id artifact))
                           (catch Exception _ nil))
                      artifact))
        pairs (case op
                :save [[a (carry-links (on-disk a) a)]]
                :link [[a (add-link (on-disk a) :artifact/children (:artifact/id b))]
                       [b (add-link (on-disk b) :artifact/parents (:artifact/id a))]])]
    (reduce (fn [result [_ artifact :as pair]]
              (let [id (:artifact/id artifact)]
                (try
                  (write-transit (artifact-file-path artifacts-dir id) artifact)
                  (log-artifact-saved logger id artifact)
                  (update result :written conj pair)
                  (catch Exception e
                    (log-artifact-save-failed logger id e)
                    (update result :failed conj id)))))
            {:written [] :failed []}
            pairs)))

(defn ^{:stratum 2} quarantine-index!
  "With the index lock held and the index unreadable: copy it aside to
   index.transit.json.corrupt-<millis>, rebuild the index from the
   per-artifact files, write the rebuilt index over the live path and
   log :artifact/index-rebuilt. Only that atomic write replaces the live
   file, so no reader sees a missing index. Returns the rebuilt index."
  [artifacts-dir logger unreadable]
  (let [path (index-file-path artifacts-dir)
        copied-to (str path ".corrupt-" (System/currentTimeMillis))
        {rebuilt :index skipped :skipped} (rebuild-index artifacts-dir)]
    (Files/copy (.toPath (io/file path)) (.toPath (io/file copied-to))
                ^"[Ljava.nio.file.CopyOption;"
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                        StandardCopyOption/COPY_ATTRIBUTES]))
    (write-transit path rebuilt)
    (log/warn logger :system :artifact/index-rebuilt
              {:message (messages/t :index/rebuilt)
               :data {:copied-to copied-to
                      :cause (:anomaly/data unreadable)
                      :artifact-count (count rebuilt)
                      :skipped skipped}})
    rebuilt))

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

(defn ^{:stratum 3} recover-index!
  "Under the index lock, re-read the index and, if it is still
   unreadable, quarantine and rebuild it (see quarantine-index!).
   Returns the index."
  [artifacts-dir logger]
  (call-with-index-lock
   artifacts-dir
   (fn []
     (let [index (read-index artifacts-dir)]
       (if (anomaly? index)
         (quarantine-index! artifacts-dir logger index)
         index)))))

(defn ^{:stratum 3} drain-action
  "Writer action: one critical section under the index lock for every
   change queued so far, including changes queued while it runs (so a
   burst of saves costs one index write). Writes each change's artifact
   files (write-change!); under the writer's monitor, puts each written
   artifact in the cache and index unless a later save or link has
   replaced the queued copy; then re-reads the on-disk index
   (quarantined and rebuilt first if unreadable), sets the written ids'
   entries to their projection from their files and writes it.
   The writer's state is {:unindexed ids :failed ids}: ids whose files
   were written but are not indexed yet (the next drain re-projects them
   from their current files), and ids whose last file write failed
   (cleared by a later successful write). close-store reports both."
  [state {:keys [artifacts-dir cache index logger pending writer] :as store}]
  (let [next-state (volatile! state)]
    (try
      (call-with-index-lock
       artifacts-dir
       (fn []
         (loop [written [] failed (:failed state)]
           (let [[batch] (swap-vals! pending empty)]
             (if (seq batch)
               (let [results (mapv (partial write-change! store) batch)]
                 (recur (into written (mapcat :written) results)
                        (reduce (fn [f r]
                                  (into (apply disj f (map (comp :artifact/id second) (:written r)))
                                        (:failed r)))
                                failed results)))
               (let [ids (into (:unindexed state) (map (comp :artifact/id second)) written)]
                 (vreset! next-state {:unindexed ids :failed failed})
                 (locking writer
                   (doseq [[queued artifact] written
                           :let [id (:artifact/id artifact)]
                           :when (identical? queued (get @cache id))]
                     (swap! cache assoc id artifact)
                     (swap! index add-to-index artifact)))
                 (when (seq ids)
                   (let [on-disk (read-index artifacts-dir)
                         disk-index (if (anomaly? on-disk) (quarantine-index! artifacts-dir logger on-disk) on-disk)]
                     (write-transit (index-file-path artifacts-dir)
                                    (reduce add-to-index disk-index
                                            (keep #(try (load-artifact-from-disk artifacts-dir %)
                                                        (catch Exception _ nil))
                                                  ids)))
                     (vswap! next-state assoc :unindexed #{})))))))))
      (catch Exception e
        (log/error logger :system :artifact/index-save-failed
                   {:message (.getMessage e)
                    :data {:artifacts-dir artifacts-dir}})))
    @next-state))

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

(defn ^{:stratum 4} load-index
  "Load the artifact metadata index; {} when there is none.
   An unreadable index is copied aside and rebuilt from the per-artifact
   files (see recover-index!) instead of failing store creation, which
   would leave every later run without persistence."
  [artifacts-dir logger]
  (let [index (read-index artifacts-dir)]
    (if (anomaly? index)
      (recover-index! artifacts-dir logger)
      index)))

(defn ^{:stratum 4} save-artifact
  "Save artifact implementation. Under the writer's monitor, carries the
   cached and indexed links into the artifact (carry-links), puts it in
   the cache and index, and queues it for drain-action. An index entry
   whose file never landed loads as nil; query skips it."
  [{:keys [cache index pending writer] :as store} saved]
  (let [id (:artifact/id saved)]
    (locking writer
      (let [artifact (carry-links (get @index id) (carry-links (get @cache id) saved))]
        (swap! cache assoc id artifact)
        (swap! index add-to-index artifact)
        (swap! pending conj [:save artifact])
        (send-off writer drain-action store)))
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
   Missing link targets and self-links are represented as anomalies
   internally, logged, and return false."
  [{:keys [cache index logger pending writer] :as record} parent-id child-id]
  (let [parent-result (find-link-target record parent-id :parent)
        ;; No caller links an artifact to itself; refuse it.
        child-result  (if (= parent-id child-id)
                        (anomaly/anomaly :invalid-input (messages/t :link/self)
                                         {:artifact/id child-id :artifact/role :child})
                        (find-link-target record child-id :child))]
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
          (let [parent (add-link (get @cache parent-id parent-result) :artifact/children child-id)
                child (add-link (get @cache child-id child-result) :artifact/parents parent-id)]
            (swap! cache assoc parent-id parent child-id child)
            (swap! index #(-> % (add-to-index parent) (add-to-index child)))
            (swap! pending conj [:link parent child])
            (send-off writer drain-action record)))

        (when logger
          (log/debug logger :system :artifact/linked
                     {:data {:parent-id parent-id
                             :child-id  child-id}}))
        true))))
