;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.gate.format
  "LSP format gate — structural formatting of written files.

   After the implement agent writes files, this gate runs LSP formatting
   to fix structural errors (unmatched parens, indentation, etc.).
   The gate repairs automatically — if formatting changes a file, it
   applies the changes and passes.

   Layer 0: LSP formatting via lsp-mcp-bridge
   Layer 1: Gate registration"
  (:require
   [ai.miniforge.gate.registry :as registry]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; LSP formatting

(def ^:private supported-extensions
  "File extensions that support LSP formatting."
  #{"clj" "cljs" "cljc" "ts" "tsx" "js" "jsx" "py" "rs" "go" "swift"})

(defn- file-extension
  "Get file extension from path."
  [path]
  (when-let [idx (str/last-index-of (str path) ".")]
    (subs (str path) (inc idx))))

(defn- formattable-file?
  "True when a file's extension supports LSP formatting."
  [file-entry]
  (contains? supported-extensions (file-extension (get file-entry :path ""))))

(defn- resolve-lsp-manager
  "Resolve LSP manager from execution context."
  [ctx]
  (or (get ctx :lsp-manager)
      (try
        (when-let [create-fn (requiring-resolve
                               'ai.miniforge.lsp-mcp-bridge.lsp.manager/create-manager)]
          (let [project-dir (or (get ctx :execution/worktree-path) ".")]
            (create-fn {} project-dir)))
        (catch Exception _ nil))))

(defn- format-file-via-lsp
  "Format a single file using LSP. Returns {:formatted? bool :error string?}."
  [lsp-manager file-path worktree-path]
  (try
    (let [full-path (str worktree-path "/" file-path)
          uri       (str "file://" full-path)
          start-fn  (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.lsp.manager/start-server)
          client-fn (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.lsp.client/format-document)]
      (if (and start-fn client-fn (.exists (io/file full-path)))
        (do
          ;; Start LSP server for this file type if not running
          (start-fn lsp-manager {:language (file-extension file-path)})
          ;; Format the document
          (let [result (client-fn (get @(:servers lsp-manager) (file-extension file-path)) uri {})]
            {:formatted? (some? result)})
          )
        {:formatted? false :error "LSP not available"}))
    (catch Exception e
      {:formatted? false :error (.getMessage e)})))

(defn check-format
  "Check if files can be formatted. Always passes — formatting is repair.

   The format gate doesn't block on check. It detects formattable files
   and notes them. The repair function does the actual formatting.

   Arguments:
     artifact - Phase artifact with :code/files
     ctx      - Execution context

   Returns:
     {:passed? true :formattable-files [...]}"
  [artifact _ctx]
  (let [files     (get artifact :code/files [])
        formattable (filterv formattable-file? files)]
    {:passed?          true
     :formattable-files (mapv :path formattable)
     :message          (str (count formattable) " file(s) support LSP formatting")}))

(defn repair-format
  "Format files via LSP. This is the action gate — it formats on repair.

   Arguments:
     artifact - Phase artifact
     errors   - Not used (format gate always passes check)
     ctx      - Execution context with :execution/worktree-path

   Returns:
     {:success? true/false :artifact artifact :formatted [...]}"
  [artifact _errors ctx]
  (let [worktree   (or (get ctx :execution/worktree-path) ".")
        lsp-mgr    (resolve-lsp-manager ctx)
        files      (get artifact :code/files [])
        formattable (filterv formattable-file? files)]
    (if-not lsp-mgr
      {:success? true :artifact artifact :message "LSP not available — skipped"}
      (let [results (mapv #(format-file-via-lsp lsp-mgr (:path %) worktree) formattable)]
        {:success?  true
         :artifact  artifact
         :formatted (mapv :path (filter :formatted? (map #(assoc %1 :path (:path %2))
                                                         results formattable)))}))))

;------------------------------------------------------------------------------ Layer 1
;; Gate registration

(registry/register-gate! :format)

(defmethod registry/get-gate :format
  [_]
  {:name        :format
   :description "LSP structural formatting of written files"
   :check       check-format
   :repair      repair-format})
