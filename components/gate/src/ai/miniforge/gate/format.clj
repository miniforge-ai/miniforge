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
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.tool-registry.interface :as tool-registry]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; LSP config resolution — driven by tool-registry LSP tools
(defn- ^{:stratum 0} file-matches-format-pattern?
  "Check if a file path matches a format-capable LSP pattern."
  [file-path pattern]
  (let [fs   (java.nio.file.FileSystems/getDefault)
        path (java.nio.file.Paths/get (str file-path) (into-array String []))]
    (.matches (.getPathMatcher fs (str "glob:" pattern)) path)))

(defn- ^{:stratum 0} create-registry
  "Create and load the tool registry used by this gate."
  [worktree-path]
  (let [registry (tool-registry/create-registry {:project-dir worktree-path})]
    (tool-registry/load-tools registry)
    registry))

(defn- ^{:stratum 0} format-capable-tools
  [registry]
  (tool-registry/find-tools registry {:type :lsp
                                      :capabilities #{:code/format}
                                      :enabled true}))

(defn- ^{:stratum 0} stop-registry-lsps!
  [registry]
  (doseq [{:keys [tool-id]} (tool-registry/list-lsp-servers registry)]
    (tool-registry/stop-lsp registry tool-id)))

(defn- ^{:stratum 0} field
  [m k]
  (or (get m k) (get m (name k))))

(defn- ^{:stratum 0} line-start-offsets
  [content]
  (loop [idx 0
         starts [0]]
    (if-let [newline-idx (str/index-of content "\n" idx)]
      (recur (inc newline-idx) (conj starts (inc newline-idx)))
      starts)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} tool-matches-file?
  [file-path tool]
  (some #(file-matches-format-pattern? file-path %)
        (get-in tool [:tool/config :lsp/file-patterns] [])))

;; File formatting via LSP
(defn- ^{:stratum 1} get-or-create-tool-registry
  "Get an injected tool registry from context, or create one."
  [ctx]
  (or (get ctx :tool-registry)
      (create-registry (or (get ctx :execution/worktree-path) "."))))

(defn- ^{:stratum 1} registry-context
  [ctx]
  (if-let [registry (get ctx :tool-registry)]
    {:registry registry :owned? false}
    {:registry (create-registry (or (get ctx :execution/worktree-path) "."))
     :owned? true}))

(defn- ^{:stratum 1} position->offset
  [line-starts content position]
  (let [line (long (or (field position :line) 0))
        character (long (or (field position :character) 0))
        line-start (get line-starts line (count content))]
    (min (count content) (+ line-start character))))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} format-tool-for-file
  [registry file-path]
  (first (filter #(tool-matches-file? file-path %)
                 (format-capable-tools registry))))

(defn- ^{:stratum 2} text-edit->span
  [line-starts content edit]
  (let [range (field edit :range)]
    {:start (position->offset line-starts content (field range :start))
     :end (position->offset line-starts content (field range :end))
     :text (or (field edit :newText) "")}))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} formattable-file?
  "True when a file matches a format-capable LSP tool config."
  [registry file-entry]
  (boolean (format-tool-for-file registry (get file-entry :path ""))))

(defn- ^{:stratum 3} apply-text-edits
  [content edits]
  (let [line-starts (line-start-offsets content)
        spans (->> edits
                   (map #(text-edit->span line-starts content %))
                   (sort-by :start >))]
    (reduce (fn [acc {:keys [start end text]}]
              (str (subs acc 0 start) text (subs acc end)))
            content
            spans)))

;------------------------------------------------------------------------------ Layer 4

(defn- ^{:stratum 4} apply-format-result!
  [file result]
  (when (:success? result)
    (let [edits (:result result)]
      (when (seq edits)
        (let [content (slurp file)]
          (spit file (apply-text-edits content edits))))
      true)))

;; Gate check/repair
(defn ^{:stratum 4} check-format
  "Check which files can be formatted. Always passes — formatting is repair.

   Returns the canonical `response/success` shape; the gate machinery's
   `passed?` predicate recognizes it via `response/success?`."
  [artifact ctx]
  (let [registry (get-or-create-tool-registry ctx)
        formattable (filterv #(formattable-file? registry %)
                             (get artifact :code/files []))]
    (response/success {:formattable-files (mapv :path formattable)
                       :message (str (count formattable)
                                     " file(s) support LSP formatting")})))

;------------------------------------------------------------------------------ Layer 5

(defn- ^{:stratum 5} format-single-file
  "Format a single file using LSP. Returns {:formatted? bool :path string}."
  [registry file-path worktree-path]
  (try
    (let [full-path (str worktree-path "/" file-path)
          uri       (str "file://" full-path)
          tool      (format-tool-for-file registry file-path)]
      (if (and tool (.exists (io/file full-path)))
        (if-let [client (tool-registry/get-lsp-client registry (:tool/id tool))]
          {:formatted? (boolean
                        (apply-format-result!
                         (io/file full-path)
                         (tool-registry/format-document client uri {})))
           :path file-path}
          {:formatted? false :path file-path})
        {:formatted? false :path file-path}))
    (catch Exception _
      {:formatted? false :path file-path})))

;------------------------------------------------------------------------------ Layer 6

(defn ^{:stratum 6} repair-format
  "Format files via LSP. Returns the canonical `response/success` shape."
  [artifact _errors ctx]
  (let [worktree    (or (get ctx :execution/worktree-path) ".")
        {:keys [registry owned?]} (registry-context ctx)]
    (try
      (let [formattable (filterv #(formattable-file? registry %)
                                 (get artifact :code/files []))
            results     (mapv #(format-single-file registry (:path %) worktree) formattable)
            formatted   (mapv :path (filter :formatted? results))]
        (response/success {:artifact artifact :formatted formatted}))
      (finally
        (when owned?
          (stop-registry-lsps! registry))))))

;------------------------------------------------------------------------------ Layer 7

(defmethod ^{:stratum 7} registry/get-gate :format
  [_]
  {:name        :format
   :description "LSP structural formatting of written files"
   :check       check-format
   :repair      repair-format})

;; Gate registration
(registry/register-gate! :format)
