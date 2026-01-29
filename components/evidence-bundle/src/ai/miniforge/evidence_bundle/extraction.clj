(ns ai.miniforge.evidence-bundle.extraction
  "Utilities for extracting and materializing artifacts from evidence bundles.
   Handles writing code artifacts to disk."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; File Operations

(defn- write-file
  "Write content to a file path.
   Creates parent directories if needed."
  [path content]
  (io/make-parents path)
  (spit path content))

(defn- delete-file
  "Delete a file if it exists.
   Returns true if deleted, false if file didn't exist."
  [path]
  (io/delete-file path true))

;------------------------------------------------------------------------------ Layer 1
;; Artifact File Extraction

(defn extract-file
  "Extract a single file from artifact and write to disk.

   File map should contain:
   - :path - File path to write
   - :content - File content
   - :action - One of :create, :modify, or :delete

   Returns map with:
   - :path - The file path
   - :action - The action taken
   - :success - true/false
   - :error - Error message if failed

   Examples:
     (extract-file {:path \"src/foo.clj\"
                    :content \"(ns foo)\"
                    :action :create})

     (extract-file {:path \"src/old.clj\"
                    :action :delete})"
  [{:keys [path content action]}]
  (try
    (case action
      :create
      (do
        (write-file path content)
        {:path path :action :create :success true})

      :modify
      (do
        (write-file path content)
        {:path path :action :modify :success true})

      :delete
      (do
        (delete-file path)
        {:path path :action :delete :success true})

      ;; Unknown action
      {:path path
       :action action
       :success false
       :error (str "Unknown action: " action)})

    (catch Exception e
      {:path path
       :action action
       :success false
       :error (.getMessage e)})))

(defn extract-files
  "Extract multiple files from artifact and write to disk.

   Artifact should be a map containing:
   - :code/files - Vector of file maps (see extract-file for structure)

   Returns map with:
   - :total - Total number of files processed
   - :successful - Number of successful operations
   - :failed - Number of failed operations
   - :results - Vector of individual file results
   - :summary - Summary string from artifact (if present)

   Example:
     (extract-files {:code/files [{:path \"src/foo.clj\"
                                   :content \"(ns foo)\"
                                   :action :create}
                                  {:path \"src/bar.clj\"
                                   :content \"(ns bar)\"
                                   :action :modify}]
                     :code/summary \"Added foo and updated bar\"})"
  [artifact]
  (let [files (:code/files artifact)
        results (mapv extract-file files)
        successful (count (filter :success results))
        failed (count (remove :success results))]
    {:total (count files)
     :successful successful
     :failed failed
     :results results
     :summary (:code/summary artifact)}))

;------------------------------------------------------------------------------ Layer 2
;; Artifact Loading and Extraction

(defn load-artifact
  "Load artifact from an EDN file.

   Arguments:
   - artifact-path: Path to EDN file containing artifact

   Returns artifact map or throws exception if file cannot be read.

   Example:
     (load-artifact \"/tmp/artifact.edn\")"
  [artifact-path]
  (-> artifact-path slurp edn/read-string))

(defn extract-artifact-from-file
  "Load artifact from file and extract all files to disk.

   This is a convenience function that combines load-artifact and extract-files.

   Arguments:
   - artifact-path: Path to EDN file containing artifact

   Returns extraction results map (see extract-files).

   Example:
     (extract-artifact-from-file \"/tmp/artifact.edn\")"
  [artifact-path]
  (let [artifact (load-artifact artifact-path)]
    (extract-files artifact)))

;------------------------------------------------------------------------------ Layer 3
;; Validation

(defn validate-artifact
  "Validate that an artifact has the required structure for extraction.

   Returns map with:
   - :valid? - true if artifact is valid
   - :errors - Vector of error messages (if invalid)

   Example:
     (validate-artifact {:code/files [...]})
     => {:valid? true}

     (validate-artifact {})
     => {:valid? false
         :errors [\"Missing :code/files\"]}"
  [artifact]
  (let [errors (cond-> []
                 (not (map? artifact))
                 (conj "Artifact must be a map")

                 (and (map? artifact)
                      (not (contains? artifact :code/files)))
                 (conj "Missing :code/files")

                 (and (map? artifact)
                      (contains? artifact :code/files)
                      (not (vector? (:code/files artifact))))
                 (conj ":code/files must be a vector"))]
    (if (empty? errors)
      {:valid? true}
      {:valid? false
       :errors errors})))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Extract a single file
  (extract-file {:path "test.txt"
                 :content "Hello, world!"
                 :action :create})

  ;; Extract multiple files from artifact
  (def artifact
    {:code/files [{:path "src/foo.clj"
                   :content "(ns foo)"
                   :action :create}
                  {:path "src/bar.clj"
                   :content "(ns bar)"
                   :action :modify}]
     :code/summary "Added foo and updated bar"})

  (extract-files artifact)

  ;; Load and extract from file
  (extract-artifact-from-file "/tmp/artifact.edn")

  ;; Validate artifact
  (validate-artifact artifact)
  (validate-artifact {})

  :end)
