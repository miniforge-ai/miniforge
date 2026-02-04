;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tool-registry.loader
  "Load tool configurations from EDN files.

   Layer 0: File discovery and path utilities
   Layer 1: EDN parsing and validation
   Layer 2: Single file loader
   Layer 3: Bulk loading orchestration

   Discovery order (later overrides earlier):
   1. Built-in: resources/tools/**/*.edn
   2. User: ~/.miniforge/tools/**/*.edn
   3. Project: .miniforge/tools/**/*.edn"
  (:require
   [ai.miniforge.tool-registry.schema :as schema]
   [ai.miniforge.logging.interface :as log]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File discovery and path utilities

(defn- user-tools-dir
  "Get the user tools directory (~/.miniforge/tools)."
  []
  (io/file (System/getProperty "user.home") ".miniforge" "tools"))

(defn- project-tools-dir
  "Get the project tools directory (.miniforge/tools)."
  [project-dir]
  (when project-dir
    (io/file project-dir ".miniforge" "tools")))

(defn- builtin-tools-dirs
  "Get built-in tools directories from classpath resources."
  []
  ;; Look for tools directory in resources
  (when-let [url (io/resource "tools")]
    (when (= "file" (.getProtocol url))
      [(io/file (.toURI url))])))

(defn- tool-file?
  "Check if a file is a tool configuration file (.edn)."
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".edn")
       (not (str/starts-with? (.getName file) "."))))

(defn- find-tool-files
  "Find all .edn files in a directory, recursively."
  [dir]
  (when (and dir (.exists dir) (.isDirectory dir))
    (->> (file-seq dir)
         (filter tool-file?)
         (vec))))

(defn- infer-tool-type
  "Infer tool type from file path.

   Path patterns:
   - tools/lsp/*.edn -> :lsp
   - tools/mcp/*.edn -> :mcp
   - tools/function/*.edn -> :function
   - tools/custom/*.edn -> :external"
  [file]
  (let [path (.getPath file)
        parent-name (some-> file .getParentFile .getName)]
    (case parent-name
      "lsp" :lsp
      "mcp" :mcp
      "function" :function
      "custom" :external
      nil)))

;------------------------------------------------------------------------------ Layer 1
;; EDN parsing and validation

(defn- safe-read-edn
  "Safely read EDN from a file.
   Returns {:success? bool :data any :error string}."
  [file]
  (try
    (let [content (slurp file)
          data (edn/read-string content)]
      (schema/success :data data))
    (catch Exception e
      (schema/failure :data (.getMessage e)))))

(defn- validate-and-normalize
  "Validate and normalize a tool configuration."
  [tool file-path]
  (let [normalized (schema/normalize-tool tool)
        {:keys [valid? errors]} (schema/validate-tool normalized)]
    (if valid?
      (schema/success :tool normalized)
      (schema/failure-with-errors :tool
                                  [{:file file-path :errors errors}]))))

;------------------------------------------------------------------------------ Layer 2
;; Single file loader

(defn load-tool-from-file
  "Load a tool configuration from a single EDN file.

   Arguments:
   - file-path - Path to the .edn file

   Returns:
   - {:success? true :tool ToolConfig}
   - {:success? false :errors [...]}

   Example:
     (load-tool-from-file \"~/.miniforge/tools/lsp/clojure.edn\")"
  [file-path]
  (let [file (io/file file-path)]
    (if-not (.exists file)
      (schema/failure-with-errors :tool [{:file file-path :error "File not found"}])
      (let [{:keys [success? data error]} (safe-read-edn file)]
        (if-not success?
          (schema/failure-with-errors :tool [{:file file-path :error error}])
          ;; Infer type if not specified
          (let [inferred-type (infer-tool-type file)
                tool (cond-> data
                       (and inferred-type (not (:tool/type data)))
                       (assoc :tool/type inferred-type))]
            (validate-and-normalize tool file-path)))))))

(defn load-tool-file
  "Load a tool file and register it in the registry.

   Arguments:
   - registry - ToolRegistry instance
   - path     - Path to .edn file

   Returns:
   - {:success? true :tool-id keyword}
   - {:success? false :error string}"
  [registry path]
  (let [{:keys [success? tool errors]} (load-tool-from-file path)]
    (if success?
      (try
        (let [register-fn (:register-fn @(:state registry))]
          (register-fn tool)
          (schema/success :tool-id (:tool/id tool)))
        (catch Exception e
          (schema/failure :tool-id (.getMessage e))))
      (schema/failure-with-errors :tool-id errors))))

;------------------------------------------------------------------------------ Layer 3
;; Discovery and bulk loading

(defn discover-tools
  "Discover tool files without loading them.

   Arguments:
   - registry - ToolRegistry instance (for project-dir)

   Returns sequence of {:path :source :inferred-type}"
  [registry]
  (let [project-dir (:project-dir @(:state registry))
        builtin-dirs (builtin-tools-dirs)
        user-dir (user-tools-dir)
        project-dir-file (project-tools-dir project-dir)

        discover-in-dir (fn [dir source]
                          (for [file (find-tool-files dir)]
                            {:path (.getPath file)
                             :source source
                             :inferred-type (infer-tool-type file)}))]

    (concat
     ;; Built-in tools
     (mapcat #(discover-in-dir % :builtin) builtin-dirs)
     ;; User tools
     (discover-in-dir user-dir :user)
     ;; Project tools
     (when project-dir-file
       (discover-in-dir project-dir-file :project)))))

(defn load-all-tools
  "Load all tools from standard locations into the registry.

   Discovery order (later overrides earlier):
   1. Built-in: resources/tools/**/*.edn
   2. User: ~/.miniforge/tools/**/*.edn
   3. Project: .miniforge/tools/**/*.edn

   Arguments:
   - registry - ToolRegistry instance

   Returns:
   - {:loaded [tool-ids...] :failed [{:path :error}...]}"
  [registry]
  (let [discovered (discover-tools registry)
        logger (:logger @(:state registry))
        results (for [{:keys [path]} discovered]
                  (let [result (load-tool-file registry path)]
                    (when logger
                      (if (:success? result)
                        (log/debug logger :system :tool-registry/loaded
                                   {:data {:tool-id (:tool-id result) :path path}})
                        (log/warn logger :system :tool-registry/load-failed
                                  {:data {:path path :error (:error result)}})))
                    (assoc result :path path)))
        loaded (vec (keep :tool-id (filter :success? results)))
        failed (vec (for [r (remove :success? results)]
                      {:path (:path r) :error (or (:error r) (:errors r))}))]
    {:loaded loaded
     :failed failed}))

(defn reload-all-tools
  "Clear registry and reload all tools from disk.

   Arguments:
   - registry - ToolRegistry instance

   Returns:
   - {:loaded [tool-ids...] :failed [{:path :error}...]}"
  [registry]
  (let [clear-fn (:clear-fn @(:state registry))]
    (clear-fn)
    (load-all-tools registry)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Discover tools
  (discover-tools {:state (atom {:project-dir "."})})

  ;; Load a single tool file
  (load-tool-from-file "path/to/tool.edn")

  ;; User tools directory
  (user-tools-dir)
  ;; => #object[java.io.File ... "~/.miniforge/tools"]

  ;; Find tool files
  (find-tool-files (user-tools-dir))

  :leave-this-here)
