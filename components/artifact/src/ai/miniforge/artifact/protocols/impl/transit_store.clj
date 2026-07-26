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
   They are used by the TransitArtifactStore record."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.artifact.messages :as messages]
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [cognitect.transit :as transit]
   [ai.miniforge.artifact.core :as core]
   [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

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

(defn ^{:stratum 0} ensure-artifacts-dir!
  "Ensure the artifacts directory exists."
  [dir]
  (.mkdirs (io/file dir))
  dir)

;; Transit serialization
(defn ^{:stratum 0} write-transit
  "Write data to a file in Transit JSON format."
  [file-path data]
  (with-open [out (io/output-stream file-path)]
    (let [writer (transit/writer out :json)]
      (transit/write writer data))))

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

(defn ^{:stratum 0} close-store
  "Close store implementation."
  [{:keys [cache logger]}]
  ;; Ensure all pending writes complete
  (Thread/sleep 100) ; Give futures time to complete
  (when logger
    (log/info logger :system :artifact/store-closed
              {:data {:artifact-count (count @cache)}})))

;------------------------------------------------------------------------------ Layer 1

;; Index management
(defn ^{:stratum 1} load-index
  "Load the artifact metadata index.
   Returns empty map if index doesn't exist."
  [artifacts-dir]
  (or (read-transit (index-file-path artifacts-dir))
      {}))

(defn ^{:stratum 1} save-index!
  "Save the artifact metadata index."
  [artifacts-dir index]
  (write-transit (index-file-path artifacts-dir) index))

;; Artifact persistence
(defn ^{:stratum 1} persist-artifact!
  "Persist an artifact to disk in Transit format."
  [artifacts-dir artifact]
  (let [id (:artifact/id artifact)
        file-path (artifact-file-path artifacts-dir id)]
    (write-transit file-path artifact)
    id))

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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} save-artifact
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

(defn ^{:stratum 2} persist-linked-artifacts
  "Persist both linked artifacts to disk."
  [record artifacts-dir parent-id child-id logger]
  (future
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
                             :child-id child-id}}))))))

;------------------------------------------------------------------------------ Layer 3

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
  [{:keys [artifacts-dir cache index logger] :as record} parent-id child-id]
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
        ;; Update index with links
        (let [new-index (update-index-links @index parent-id child-id)]
          (reset! index new-index)
          (future (save-index! artifacts-dir new-index)))

        ;; Update cached artifacts if present
        (update-cache-links cache parent-id child-id)

        ;; Re-persist both artifacts to disk
        (persist-linked-artifacts record artifacts-dir parent-id child-id logger)

        (when logger
          (log/debug logger :system :artifact/linked
                     {:data {:parent-id parent-id
                             :child-id  child-id}}))
        true))))
