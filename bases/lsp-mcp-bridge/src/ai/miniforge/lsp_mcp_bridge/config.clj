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
   [ai.miniforge.messages.interface :as messages]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private t
  "Translator for this base's message catalog. Config-load diagnostics are
   routed through it (system-locale, catalog-audited) rather than inlined as
   raw English string literals."
  (messages/create-translator "config/lsp-mcp-bridge/messages/system.edn"
                              :lsp-mcp-bridge/system))

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

(defn read-config-resource
  "Read a required EDN config map from a classpath resource, failing fast with
   a catalog-routed ex-info when the resource is absent, malformed, not a map,
   or missing a required key — rather than a low-signal NPE/reader error at
   load. The strict counterpart of `read-edn-file` (which is lenient by design
   for the 3-tier tool-config discovery)."
  [path required-keys]
  (let [url (io/resource path)]
    (when (nil? url)
      (throw (ex-info (t :lsp-config.system/missing-resource {:path path})
                      {:config/resource path})))
    (let [parsed (try
                   (edn/read-string (slurp url))
                   (catch Exception e
                     (throw (ex-info (t :lsp-config.system/malformed {:path path})
                                     {:config/resource path} e))))]
      (when-not (map? parsed)
        (throw (ex-info (t :lsp-config.system/not-a-map {:path path})
                        {:config/resource path})))
      (let [missing (remove #(contains? parsed %) required-keys)]
        (when (seq missing)
          (throw (ex-info (t :lsp-config.system/missing-keys
                             {:path path :keys (vec missing)})
                          {:config/resource path :config/missing-keys (vec missing)}))))
      parsed)))

(def lsp-timeout-keys
  "Required keys in the LSP client timeout resource."
  [:request-timeout-ms :init-timeout-ms :shutdown-timeout-ms])

(defn read-timeout-resource
  "Read and validate an LSP client timeout EDN resource at `path`. Each value
   must be a positive-integer count of milliseconds; otherwise fail fast at
   load (catalog-routed ex-info) rather than later at `deref`."
  [path]
  (let [parsed (read-config-resource path lsp-timeout-keys)
        invalid (remove #(pos-int? (get parsed %)) lsp-timeout-keys)]
    (when (seq invalid)
      (throw (ex-info (t :lsp-config.system/invalid-values
                         {:path path :keys (vec invalid)})
                      {:config/resource path :config/invalid-keys (vec invalid)})))
    parsed))

(def lsp-timeouts-resource
  "Classpath path to the shipped LSP client timeout resource."
  "config/lsp-mcp-bridge/lsp.edn")

(defn load-lsp-timeouts
  "Load and validate the shipped LSP client timeouts."
  []
  (read-timeout-resource lsp-timeouts-resource))

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
