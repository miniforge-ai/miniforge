(ns ai.miniforge.lsp-mcp-bridge.config
  "Configuration reader for the LSP-to-MCP bridge.

   Reads EDN tool configs from 3-tier discovery:
   1. Built-in (classpath resources/tools/lsp/*.edn)
   2. User (~/.miniforge/tools/lsp/*.edn)
   3. Project (.miniforge/tools/lsp/*.edn)

   Layer 0: File discovery and reading
   Layer 1: Language routing
   Layer 2: Config assembly"
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File discovery and reading

(defn read-edn-file
  "Read and parse an EDN file. Returns nil on error."
  [path]
  (try
    (edn/read-string (slurp (str path)))
    (catch Exception e
      (binding [*out* *err*]
        (println "Warning: Failed to read" (str path) "-" (.getMessage e)))
      nil)))

(defn discover-resource-configs
  "Discover built-in LSP configs from classpath resources."
  []
  (let [resource-dir (io/resource "tools/lsp")]
    (when resource-dir
      ;; When running from a jar, we list known configs
      ;; When running from source, we can list the directory
      (let [dir-path (str resource-dir)]
        (if (str/starts-with? dir-path "file:")
          ;; Running from filesystem — list directory
          (let [dir (io/file (java.net.URI. dir-path))]
            (->> (file-seq dir)
                 (filter #(str/ends-with? (str %) ".edn"))
                 (map #(edn/read-string (slurp %)))))
          ;; Running from jar — read known configs
          (keep (fn [name]
                  (when-let [r (io/resource (str "tools/lsp/" name))]
                    (edn/read-string (slurp r))))
                ["clojure.edn" "typescript.edn" "python.edn"
                 "go.edn" "rust.edn" "lua.edn" "java.edn"]))))))

(defn discover-directory-configs
  "Discover LSP configs from a directory."
  [dir-path]
  (let [dir (io/file (str dir-path))]
    (when (and dir (.exists dir) (.isDirectory dir))
      (->> (file-seq dir)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
           (keep read-edn-file)))))

(defn user-tools-dir
  "Get the user tools directory (~/.miniforge/tools/lsp/)."
  []
  (str (fs/home) "/.miniforge/tools/lsp"))

(defn project-tools-dir
  "Get the project tools directory (.miniforge/tools/lsp/)."
  [project-dir]
  (str project-dir "/.miniforge/tools/lsp"))

;------------------------------------------------------------------------------ Layer 1
;; Language routing

(def extension->language
  "Map file extensions to LSP language identifiers."
  {"clj"  "clojure"
   "cljs" "clojurescript"
   "cljc" "clojure"
   "edn"  "clojure"
   "ts"   "typescript"
   "tsx"  "typescriptreact"
   "js"   "javascript"
   "jsx"  "javascriptreact"
   "mts"  "typescript"
   "mjs"  "javascript"
   "py"   "python"
   "pyi"  "python"
   "go"   "go"
   "rs"   "rust"
   "lua"  "lua"
   "java" "java"})

(defn file-extension
  "Get the file extension from a path."
  [file-path]
  (let [name (fs/file-name file-path)]
    (when-let [idx (str/last-index-of name ".")]
      (subs name (inc idx)))))

(defn language-id
  "Get the LSP language ID for a file path."
  [file-path]
  (get extension->language (file-extension file-path)))

(defn build-language-to-tool-index
  "Build a lookup table from language ID to tool config."
  [tools]
  (reduce (fn [index tool]
            (let [languages (get-in tool [:tool/config :lsp/languages])]
              (reduce (fn [idx lang]
                        (assoc idx lang tool))
                      index
                      languages)))
          {}
          tools))

;------------------------------------------------------------------------------ Layer 2
;; Config assembly

(defn read-lsp-registry
  "Read the LSP installation registry from classpath."
  []
  (when-let [r (io/resource "lsp-registry.edn")]
    (edn/read-string (slurp r))))

(defn load-config
  "Load all LSP tool configurations.

   Arguments:
   - project-dir - Project root directory (optional)

   Returns:
   {:tools        [tool-config ...]      ;; All LSP tool configs
    :tool-index   {tool-id -> config}    ;; By tool ID
    :lang-index   {language -> config}   ;; By language ID
    :registry     registry-data}         ;; Installation registry"
  ([]
   (load-config nil))
  ([project-dir]
   (let [;; Discover from 3 tiers (later overrides earlier)
         builtin-tools  (or (discover-resource-configs) [])
         user-tools     (or (discover-directory-configs (user-tools-dir)) [])
         project-tools  (if project-dir
                          (or (discover-directory-configs (project-tools-dir project-dir)) [])
                          [])
         ;; Merge: later tools override earlier by :tool/id
         all-tools (->> (concat builtin-tools user-tools project-tools)
                        (reduce (fn [m tool]
                                  (assoc m (:tool/id tool) tool))
                                {})
                        vals
                        (filter #(get % :tool/enabled true))
                        vec)
         tool-index (into {} (map (juxt :tool/id identity)) all-tools)
         lang-index (build-language-to-tool-index all-tools)
         registry   (read-lsp-registry)]

     {:tools all-tools
      :tool-index tool-index
      :lang-index lang-index
      :registry registry})))

(defn resolve-tool-for-file
  "Find the LSP tool config for a given file path.

   Arguments:
   - config    - Config map from load-config
   - file-path - Absolute file path

   Returns tool config map or nil."
  [config file-path]
  (when-let [lang (language-id file-path)]
    (get (:lang-index config) lang)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def cfg (load-config))
  (:tools cfg)
  (resolve-tool-for-file cfg "/foo/bar.clj")
  (resolve-tool-for-file cfg "/foo/bar.ts")
  (language-id "/foo/bar.py")

  :leave-this-here)
