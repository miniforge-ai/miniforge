(ns ai.miniforge.artifact.protocols.impl.transit-store
  "Implementation functions for TransitArtifactStore protocol.

   These functions implement the ArtifactStore protocol for the Transit-based store.
   They are used by the TransitArtifactStore record."
  (:require
   [clojure.java.io :as io]
   [cognitect.transit :as transit]
   [ai.miniforge.artifact.core :as core]
   [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions for path handling

(defn artifacts-dir
  "Get the artifacts directory path.
   Defaults to ~/.miniforge/artifacts"
  ([] (artifacts-dir nil))
  ([base-dir]
   (if base-dir
     (str base-dir "/artifacts")
     (str (System/getProperty "user.home") "/.miniforge/artifacts"))))

(defn artifact-file-path
  "Get the file path for an artifact by ID."
  [artifacts-dir artifact-id]
  (str artifacts-dir "/" artifact-id ".transit.json"))

(defn index-file-path
  "Get the path to the metadata index file."
  [artifacts-dir]
  (str artifacts-dir "/index.transit.json"))

(defn ensure-artifacts-dir!
  "Ensure the artifacts directory exists."
  [dir]
  (.mkdirs (io/file dir))
  dir)

;------------------------------------------------------------------------------ Layer 1
;; Transit serialization

(defn write-transit
  "Write data to a file in Transit JSON format."
  [file-path data]
  (with-open [out (io/output-stream file-path)]
    (let [writer (transit/writer out :json)]
      (transit/write writer data))))

(defn read-transit
  "Read data from a Transit JSON file.
   Returns nil if file doesn't exist."
  [file-path]
  (when (.exists (io/file file-path))
    (with-open [in (io/input-stream file-path)]
      (let [reader (transit/reader in :json)]
        (transit/read reader)))))

;------------------------------------------------------------------------------ Layer 2
;; Index management

(defn load-index
  "Load the artifact metadata index.
   Returns empty map if index doesn't exist."
  [artifacts-dir]
  (or (read-transit (index-file-path artifacts-dir))
      {}))

(defn save-index!
  "Save the artifact metadata index."
  [artifacts-dir index]
  (write-transit (index-file-path artifacts-dir) index))

(defn add-to-index
  "Add artifact metadata to the index."
  [index artifact]
  (let [id (:artifact/id artifact)
        metadata {:artifact/id id
                  :artifact/type (:artifact/type artifact)
                  :artifact/version (:artifact/version artifact)
                  :artifact/parents (:artifact/parents artifact)
                  :artifact/children (:artifact/children artifact)}]
    (assoc index id metadata)))

(defn update-index-links
  "Update parent-child links in the index."
  [index parent-id child-id]
  (-> index
      (update-in [parent-id :artifact/children] (fnil conj []) child-id)
      (update-in [child-id :artifact/parents] (fnil conj []) parent-id)))

;------------------------------------------------------------------------------ Layer 3
;; Artifact persistence

(defn persist-artifact!
  "Persist an artifact to disk in Transit format."
  [artifacts-dir artifact]
  (let [id (:artifact/id artifact)
        file-path (artifact-file-path artifacts-dir id)]
    (write-transit file-path artifact)
    id))

(defn load-artifact-from-disk
  "Load an artifact from disk.
   Returns nil if not found."
  [artifacts-dir artifact-id]
  (let [file-path (artifact-file-path artifacts-dir artifact-id)]
    (read-transit file-path)))

;------------------------------------------------------------------------------ Layer 4
;; Protocol implementations

(defn- log-artifact-saved
  "Log artifact save operation."
  [logger artifact-id artifact]
  (when logger
    (log/debug logger :system :artifact/saved
               {:data {:artifact-id artifact-id
                       :artifact-type (:artifact/type artifact)
                       :storage :transit}})))

(defn- log-artifact-save-failed
  "Log artifact save failure."
  [logger artifact-id e]
  (when logger
    (log/error logger :system :artifact/save-failed
               {:message (.getMessage e)
                :data {:artifact-id artifact-id}})))

(defn save-artifact
  "Save artifact implementation."
  [{:keys [artifacts-dir cache index logger]} artifact]
  (let [id (:artifact/id artifact)]
    ;; Store in memory cache
    (swap! cache assoc id artifact)

    ;; Update index
    (let [new-index (add-to-index @index artifact)]
      (reset! index new-index)
      (future (save-index! artifacts-dir new-index)))

    ;; Persist to disk asynchronously
    (future
      (try
        (persist-artifact! artifacts-dir artifact)
        (log-artifact-saved logger id artifact)
        (catch Exception e
          (log-artifact-save-failed logger id e))))
    id))

(defn load-artifact-impl
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

(defn- filter-by-criteria
  "Filter artifacts by criteria."
  [index criteria]
  (keep (fn [[id metadata]]
          (when (every? (fn [[k v]]
                          (= (get metadata k) v))
                        criteria)
            id))
        index))

(defn query-artifacts
  "Query artifacts implementation."
  [{:keys [index] :as record} criteria]
  (if (empty? criteria)
    ;; Return all artifacts
    (let [all-ids (keys @index)]
      (keep #(p/load-artifact record %) all-ids))
    ;; Filter by criteria using index first for efficiency
    (let [matching-ids (filter-by-criteria @index criteria)
          artifacts (keep #(p/load-artifact record %) matching-ids)]
      (vec artifacts))))

(defn- update-cache-links
  "Update cached artifacts with new links."
  [cache parent-id child-id]
  (when-let [parent (get @cache parent-id)]
    (swap! cache assoc parent-id (core/add-child parent child-id)))
  (when-let [child (get @cache child-id)]
    (swap! cache assoc child-id (core/add-parent child parent-id))))

(defn- persist-linked-artifacts
  "Persist both linked artifacts to disk."
  [record artifacts-dir parent-id child-id]
  (future
    (when-let [parent (p/load-artifact record parent-id)]
      (persist-artifact! artifacts-dir parent))
    (when-let [child (p/load-artifact record child-id)]
      (persist-artifact! artifacts-dir child))))

(defn link-artifacts
  "Link artifacts implementation."
  [{:keys [artifacts-dir cache index logger] :as record} parent-id child-id]
  (try
    ;; Load both artifacts to ensure they exist
    (when-not (p/load-artifact record parent-id)
      (throw (ex-info "Parent artifact not found" {:parent-id parent-id})))
    (when-not (p/load-artifact record child-id)
      (throw (ex-info "Child artifact not found" {:child-id child-id})))

    ;; Update index with links
    (let [new-index (update-index-links @index parent-id child-id)]
      (reset! index new-index)
      (future (save-index! artifacts-dir new-index)))

    ;; Update cached artifacts if present
    (update-cache-links cache parent-id child-id)

    ;; Re-persist both artifacts to disk
    (persist-linked-artifacts record artifacts-dir parent-id child-id)

    (when logger
      (log/debug logger :system :artifact/linked
                 {:data {:parent-id parent-id
                         :child-id child-id}}))
    true
    (catch Exception e
      (when logger
        (log/error logger :system :artifact/link-failed
                   {:message (.getMessage e)
                    :data {:parent-id parent-id
                           :child-id child-id}}))
      false)))

(defn close-store
  "Close store implementation."
  [{:keys [cache logger]}]
  ;; Ensure all pending writes complete
  (Thread/sleep 100) ; Give futures time to complete
  (when logger
    (log/info logger :system :artifact/store-closed
              {:data {:artifact-count (count @cache)}})))
