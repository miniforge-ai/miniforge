;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.gate.format
  "LSP format gate — structural formatting of written files.

   File format support is determined from LSP tool configs in
   resources/tools/lsp/*.edn — no hardcoded extension lists.

   Layer 0: LSP config resolution (data-driven)
   Layer 1: File formatting via LSP
   Layer 2: Gate check/repair + registration"
  (:require
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.lsp-mcp-bridge.lsp.manager :as lsp-manager]
   [ai.miniforge.lsp-mcp-bridge.lsp.client :as lsp-client]
   [ai.miniforge.response.builder :as response]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; LSP config resolution — driven by tools/lsp/*.edn data

(def ^:private lsp-configs-resource-dir "tools/lsp")

(defn- load-format-patterns
  "Load file patterns from LSP tool configs that support :format capability."
  []
  (try
    (let [dir (io/file (io/resource lsp-configs-resource-dir))]
      (->> (file-seq dir)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (map #(edn/read-string (slurp %)))
           (filter #(contains? (get-in % [:tool/config :lsp/capabilities] #{}) :format))
           (mapcat #(get-in % [:tool/config :lsp/file-patterns] []))
           vec))
    (catch Exception _ [])))

(def ^:private format-capable-patterns
  "File patterns that support LSP formatting. Loaded once from classpath."
  (delay (load-format-patterns)))

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
;; File formatting via LSP

(defn- file-extension
  "Get file extension from path."
  [path]
  (when-let [idx (str/last-index-of (str path) ".")]
    (subs (str path) (inc idx))))

(defn- get-or-create-lsp-manager
  "Get LSP manager from context, or create one."
  [ctx]
  (or (get ctx :lsp-manager)
      (lsp-manager/create-manager {} (or (get ctx :execution/worktree-path) "."))))

(defn- format-single-file
  "Format a single file using LSP. Returns {:formatted? bool :path string}."
  [manager file-path worktree-path]
  (try
    (let [full-path (str worktree-path "/" file-path)
          uri       (str "file://" full-path)
          ext       (file-extension file-path)]
      (if (.exists (io/file full-path))
        (do
          (lsp-manager/start-server manager {:language ext})
          (when-let [server (get @(:servers manager) ext)]
            (lsp-client/format-document server uri {}))
          {:formatted? true :path file-path})
        {:formatted? false :path file-path}))
    (catch Exception _
      {:formatted? false :path file-path})))

;------------------------------------------------------------------------------ Layer 2
;; Gate check/repair

(defn check-format
  "Check which files can be formatted. Always passes — formatting is repair."
  [artifact _ctx]
  (let [formattable (filterv formattable-file? (get artifact :code/files []))]
    (response/success {:formattable-files (mapv :path formattable)
                       :message (str (count formattable) " file(s) support LSP formatting")})))

(defn repair-format
  "Format files via LSP."
  [artifact _errors ctx]
  (let [worktree    (or (get ctx :execution/worktree-path) ".")
        manager     (get-or-create-lsp-manager ctx)
        formattable (filterv formattable-file? (get artifact :code/files []))
        results     (mapv #(format-single-file manager (:path %) worktree) formattable)
        formatted   (mapv :path (filter :formatted? results))]
    (response/success {:artifact artifact :formatted formatted})))

;------------------------------------------------------------------------------ Layer 2
;; Gate registration

(registry/register-gate! :format)

(defmethod registry/get-gate :format
  [_]
  {:name        :format
   :description "LSP structural formatting of written files"
   :check       check-format
   :repair      repair-format})
