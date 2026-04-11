;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.gate.format
  "LSP format gate — structural formatting of written files.

   After the implement agent writes files, this gate runs LSP formatting
   to fix structural errors (unmatched parens, indentation, etc.).

   File format support is determined from LSP tool configs in
   resources/tools/lsp/*.edn — no hardcoded extension lists.

   Layer 0: LSP config resolution
   Layer 1: File formatting
   Layer 2: Gate registration"
  (:require
   [ai.miniforge.gate.registry :as registry]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; LSP config resolution — driven by tools/lsp/*.edn data

(def ^:private lsp-configs-resource-dir "tools/lsp")

(def ^:private format-capable-patterns
  "File patterns from LSP tool configs that support :format capability.
   Loaded from classpath resources/tools/lsp/*.edn."
  (delay
    (try
      (let [dir (io/file (io/resource lsp-configs-resource-dir))]
        (->> (file-seq dir)
             (filter #(str/ends-with? (.getName %) ".edn"))
             (map #(edn/read-string (slurp %)))
             (filter #(contains? (get-in % [:tool/config :lsp/capabilities] #{}) :format))
             (mapcat #(get-in % [:tool/config :lsp/file-patterns] []))
             vec))
      (catch Exception _ []))))

(defn- file-matches-format-pattern?
  "Check if a file path matches any format-capable LSP pattern."
  [file-path]
  (let [fs   (java.nio.file.FileSystems/getDefault)
        path (java.nio.file.Paths/get (str file-path) (into-array String []))]
    (some #(.matches (.getPathMatcher fs (str "glob:" %)) path)
          @format-capable-patterns)))

(defn- formattable-file?
  "True when a file matches a format-capable LSP tool config."
  [file-entry]
  (file-matches-format-pattern? (get file-entry :path "")))

;------------------------------------------------------------------------------ Layer 1
;; File formatting

(defn- resolve-lsp-manager
  "Resolve LSP manager from execution context."
  [ctx]
  (or (get ctx :lsp-manager)
      (try
        (when-let [create-fn (requiring-resolve
                               'ai.miniforge.lsp-mcp-bridge.lsp.manager/create-manager)]
          (create-fn {} (or (get ctx :execution/worktree-path) ".")))
        (catch Exception _ nil))))

(defn- file-extension [path]
  (when-let [idx (str/last-index-of (str path) ".")]
    (subs (str path) (inc idx))))

(defn- format-file-via-lsp
  "Format a single file using LSP. Returns {:formatted? bool :path string}."
  [lsp-manager file-path worktree-path]
  (try
    (let [full-path (str worktree-path "/" file-path)
          uri       (str "file://" full-path)
          ext       (file-extension file-path)]
      (if (.exists (io/file full-path))
        (let [start-fn  (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.lsp.manager/start-server)
              client-fn (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.lsp.client/format-document)]
          (when (and start-fn client-fn)
            (start-fn lsp-manager {:language ext})
            (when-let [server (get @(:servers lsp-manager) ext)]
              (client-fn server uri {})))
          {:formatted? true :path file-path})
        {:formatted? false :path file-path}))
    (catch Exception _
      {:formatted? false :path file-path})))

(defn check-format
  "Check if files can be formatted. Always passes — formatting is repair.

   Arguments:
     artifact - Phase artifact with :code/files
     ctx      - Execution context

   Returns:
     {:passed? true :formattable-files [...]}"
  [artifact _ctx]
  (let [files       (get artifact :code/files [])
        formattable (filterv formattable-file? files)]
    {:passed?           true
     :formattable-files (mapv :path formattable)
     :message           (str (count formattable) " file(s) support LSP formatting")}))

(defn repair-format
  "Format files via LSP.

   Arguments:
     artifact - Phase artifact
     errors   - Not used (format gate always passes check)
     ctx      - Execution context with :execution/worktree-path

   Returns:
     {:success? true :artifact artifact :formatted [...]}"
  [artifact _errors ctx]
  (let [worktree    (or (get ctx :execution/worktree-path) ".")
        lsp-mgr     (resolve-lsp-manager ctx)
        files       (get artifact :code/files [])
        formattable (filterv formattable-file? files)]
    (if-not lsp-mgr
      {:success? true :artifact artifact :message "LSP not available — skipped"}
      (let [results   (mapv #(format-file-via-lsp lsp-mgr (:path %) worktree) formattable)
            formatted (mapv :path (filter :formatted? results))]
        {:success?  true
         :artifact  artifact
         :formatted formatted}))))

;------------------------------------------------------------------------------ Layer 2
;; Gate registration

(registry/register-gate! :format)

(defmethod registry/get-gate :format
  [_]
  {:name        :format
   :description "LSP structural formatting of written files"
   :check       check-format
   :repair      repair-format})
