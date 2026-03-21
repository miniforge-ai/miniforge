(ns ai.miniforge.artifact.transit-store
  "Transit-based artifact store for Babashka compatibility.

   Architecture:
   - In-memory cache (atom) for fast access during execution
   - Transit JSON files for persistence (~/.miniforge/artifacts/)
   - Lazy loading: load from disk only when not in memory
   - Async writes: persist to disk without blocking

   File structure:
     ~/.miniforge/artifacts/
       ├── {uuid}.transit.json     - Individual artifact files
       └── index.transit.json      - Metadata index for queries

   Benefits:
   - Babashka compatible (no JVM-only deps)
   - Fast in-memory access during workflow execution
   - Survives process restarts
   - Can be streamed to central location later
   - Simple file-based persistence"
  (:require
   [clojure.java.io :as io]
   [cognitect.transit :as transit]
   [ai.miniforge.artifact.core :as core]
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
  "Add artifact metadata to the index.
   Index structure: {artifact-id {:artifact/id :artifact/type :artifact/version :artifact/parents :artifact/children}}"
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
;; TransitArtifactStore implementation

(defrecord TransitArtifactStore [artifacts-dir cache index logger]
  core/ArtifactStore

  (save [_this artifact]
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
          (when logger
            (log/debug logger :system :artifact/saved
                       {:data {:artifact-id id
                               :artifact-type (:artifact/type artifact)
                               :storage :transit}}))
          (catch Exception e
            (when logger
              (log/error logger :system :artifact/save-failed
                         {:message (.getMessage e)
                          :data {:artifact-id id}})))))
      id))

  (load-artifact [_this id]
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

  (query [this criteria]
    (if (empty? criteria)
      ;; Return all artifacts
      (let [all-ids (keys @index)]
        (keep #(core/load-artifact this %) all-ids))
      ;; Filter by criteria using index first for efficiency
      (let [;; Filter index by criteria
            matching-ids (keep (fn [[id metadata]]
                                 (when (every? (fn [[k v]]
                                                 (= (get metadata k) v))
                                               criteria)
                                   id))
                               @index)
            ;; Load full artifacts for matches
            artifacts (keep #(core/load-artifact this %) matching-ids)]
        (vec artifacts))))

  (link [_this parent-id child-id]
    (try
      ;; Load both artifacts to ensure they exist
      (when-not (core/load-artifact _this parent-id)
        (throw (ex-info "Parent artifact not found" {:parent-id parent-id})))
      (when-not (core/load-artifact _this child-id)
        (throw (ex-info "Child artifact not found" {:child-id child-id})))

      ;; Update index with links
      (let [new-index (update-index-links @index parent-id child-id)]
        (reset! index new-index)
        (future (save-index! artifacts-dir new-index)))

      ;; Update cached artifacts if present
      (when-let [parent (get @cache parent-id)]
        (swap! cache assoc parent-id (core/add-child parent child-id)))
      (when-let [child (get @cache child-id)]
        (swap! cache assoc child-id (core/add-parent child parent-id)))

      ;; Re-persist both artifacts to disk
      (future
        (when-let [parent (core/load-artifact _this parent-id)]
          (persist-artifact! artifacts-dir parent))
        (when-let [child (core/load-artifact _this child-id)]
          (persist-artifact! artifacts-dir child)))

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

  (close [_this]
    ;; Ensure all pending writes complete
    (Thread/sleep 100) ; Give futures time to complete
    (when logger
      (log/info logger :system :artifact/store-closed
                {:data {:artifact-count (count @cache)}}))))

;------------------------------------------------------------------------------ Layer 5
;; Public API

(defn create-transit-store
  "Create a new Transit-based artifact store.

   Options:
   - :dir      - Base directory for storage (defaults to ~/.miniforge)
   - :logger   - Optional logger

   The artifacts will be stored in {dir}/artifacts/

   Examples:
     (create-transit-store)                              ; Uses ~/.miniforge/artifacts
     (create-transit-store {:dir \"/tmp/test\"})          ; Uses /tmp/test/artifacts
     (create-transit-store {:logger my-logger})"
  ([] (create-transit-store {}))
  ([{:keys [dir logger]}]
   (let [artifacts-dir (artifacts-dir dir)]
     ;; Ensure directory exists
     (ensure-artifacts-dir! artifacts-dir)

     ;; Load existing index
     (let [index (load-index artifacts-dir)]
       (when logger
         (log/info logger :system :artifact/store-created
                   {:data {:type :transit
                           :dir artifacts-dir
                           :existing-artifacts (count index)}}))

       (->TransitArtifactStore
        artifacts-dir
        (atom {})           ; Empty cache initially
        (atom index)        ; Load existing index
        logger)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create transit store
  (def store (create-transit-store))

  ;; Create transit store with custom directory
  (def store (create-transit-store {:dir "/tmp/miniforge-test"}))

  ;; Save an artifact
  (def art-id (random-uuid))
  (core/save store {:artifact/id art-id
                    :artifact/type :code
                    :artifact/version "1.0.0"
                    :artifact/content {:file "foo.clj"
                                       :code "(defn hello [] \"world\")"}
                    :artifact/origin {:intent-id (random-uuid)
                                      :agent-id (random-uuid)
                                      :task-id (random-uuid)}
                    :artifact/parents []
                    :artifact/metadata {:language :clojure}})

  ;; Load an artifact
  (core/load-artifact store art-id)

  ;; Query artifacts
  (core/query store {:artifact/type :code})

  ;; Link artifacts
  (def parent-id (random-uuid))
  (def child-id (random-uuid))
  (core/save store {:artifact/id parent-id
                    :artifact/type :spec
                    :artifact/version "1.0.0"
                    :artifact/content "spec content"})
  (core/save store {:artifact/id child-id
                    :artifact/type :code
                    :artifact/version "1.0.0"
                    :artifact/content "code content"})
  (core/link store parent-id child-id)

  ;; Verify link
  (core/load-artifact store parent-id) ; Should show child-id in :artifact/children
  (core/load-artifact store child-id)  ; Should show parent-id in :artifact/parents

  ;; Close store
  (core/close store)

  ;; Persistence test - create new store instance
  (def store2 (create-transit-store {:dir "/tmp/miniforge-test"}))
  (core/load-artifact store2 art-id) ; Should load from disk

  :end)
