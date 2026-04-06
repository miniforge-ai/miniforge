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

(ns ai.miniforge.lsp-mcp-bridge.tasks
  "bb task implementations for LSP server management.

   Provides CLI commands for installing LSP servers and configuring
   Claude Code / Claude Desktop / Codex to use the miniforge LSP-MCP bridge.

   Layer 0: Name resolution and display helpers
   Layer 1: lsp:status — show installed/available LSP servers
   Layer 2: lsp:install — install LSP servers
   Layer 3: lsp:setup — generate MCP config for Claude Code / Claude Desktop / Codex"
  (:require
   [ai.miniforge.lsp-mcp-bridge.config :as config]
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]
   [ai.miniforge.response.interface :as response]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Name resolution and display helpers

(defn build-name-index
  "Build lookup from user-friendly names to registry server entries.

   Accepts short language names (clojure, typescript, go) and exact
   registry keys (clojure-lsp, gopls, pyright). Case-insensitive."
  [registry]
  (reduce-kv
   (fn [idx server-key entry]
     (let [tool-id-name (name (:tool-id entry))
           server-key-str (name server-key)]
       (-> idx
           (assoc (str/lower-case tool-id-name) {:server-key server-key :entry entry})
           (assoc (str/lower-case server-key-str) {:server-key server-key :entry entry}))))
   {}
   (:servers registry)))

(defn resolve-server-names
  "Resolve CLI arg names to server entries.
   Returns seq of {:server-key :entry} or prints error for unknown names."
  [registry names]
  (let [idx (build-name-index registry)]
    (keep (fn [n]
            (let [n (str/lower-case n)]
              (if-let [found (get idx n)]
                found
                (do (println (str "  Unknown LSP server: " n))
                    (println (str "  Available: "
                                  (str/join ", " (sort (map #(name (-> % val :tool-id))
                                                            (:servers registry))))))
                    nil))))
          names)))

(defn all-server-entries
  "Get all server entries from the registry."
  [registry]
  (map (fn [[k v]] {:server-key k :entry v})
       (:servers registry)))

(defn method-label
  "Human-readable label for an install method."
  [entry]
  (case (get entry :install-method :github)
    :github     "github release"
    :npm        "npm"
    :go-install "go install"
    :custom     "manual install"
    (name (get entry :install-method :github))))

(defn pad
  "Right-pad a string to the given width."
  [s width]
  (str s (apply str (repeat (max 1 (- width (count s))) " "))))

;------------------------------------------------------------------------------ Layer 1
;; lsp:status — show installed/available LSP servers

(defn status
  "Show installation status of all LSP servers."
  []
  (let [cfg (config/load-config)
        registry (:registry cfg)
        platform (installer/platform-key)]
    (println (str "LSP Server Status (platform: " platform ")"))
    (println (apply str (repeat 55 "\u2500")))
    (doseq [[server-key entry] (sort-by key (:servers registry))]
      (let [binary (:binary entry)
            resolved (installer/resolve-binary binary)]
        (if resolved
          (println (str "  \u2713 " (pad (name server-key) 30) resolved))
          (println (str "  \u2717 " (pad (name server-key) 30)
                        "not installed (" (method-label entry) ")")))))))

;------------------------------------------------------------------------------ Layer 2
;; lsp:install — install LSP servers

(defn install
  "Install LSP servers. With no args, installs all enabled servers.
   With args, installs only the named servers.

   Arguments:
   - args - Command-line args (server names or empty for all)"
  [args]
  (let [cfg (config/load-config)
        registry (:registry cfg)
        targets (if (seq args)
                  (resolve-server-names registry args)
                  (all-server-entries registry))]
    (when (empty? targets)
      (println "No LSP servers to install.")
      (System/exit 1))
    (println (str "Installing " (count targets) " LSP server(s)...\n"))
    (let [results
          (doall
           (for [{:keys [entry]} targets]
             (let [binary (:binary entry)
                   tool-id (:tool-id entry)
                   resolved (installer/resolve-binary binary)]
               (if resolved
                 (do (println (str "  \u2713 " (name tool-id) " already installed (" resolved ")"))
                     :already-installed)
                 (do (print (str "  \u2026 Installing " (name tool-id) "..."))
                     (flush)
                     (let [result (installer/ensure-installed registry tool-id [binary])]
                       (if (response/anomaly-map? result)
                         (do (println (str "\r  \u2717 " (name tool-id) " failed: "
                                          (:anomaly/message result)))
                             :failed)
                         (do (println (str "\r  \u2713 " (name tool-id) " installed ("
                                          (first (:command result)) ")"))
                             :installed))))))))]
      (println)
      (let [freqs (frequencies results)]
        (println (str "Done: "
                      (get freqs :installed 0) " installed, "
                      (get freqs :already-installed 0) " already present, "
                      (get freqs :failed 0) " failed"))
        (when (pos? (get freqs :failed 0))
          (System/exit 1))))))

;------------------------------------------------------------------------------ Layer 3
;; lsp:setup — generate MCP config for Claude Code / Claude Desktop / Codex

(defn project-dir
  "Get the absolute project directory."
  []
  (System/getProperty "user.dir"))

(defn mcp-server-entry-claude-code
  "Build MCP server config for Claude Code CLI (.mcp.json)."
  []
  {"command" "mf"
   "args"    ["lsp-mcp-bridge"]
   "env"     {"MINIFORGE_PROJECT_DIR" "."}})

(defn mcp-server-entry-claude-desktop
  "Build MCP server config for Claude Desktop."
  []
  (let [dir (project-dir)]
    {"command" "mf"
     "args"    ["lsp-mcp-bridge"]
     "cwd"     dir
     "env"     {"MINIFORGE_PROJECT_DIR" dir}}))

(defn claude-desktop-config-path
  "Path to Claude Desktop config file."
  []
  (let [platform (installer/platform-key)]
    (if (str/starts-with? platform "macos")
      (str (fs/home) "/Library/Application Support/Claude/claude_desktop_config.json")
      (str (fs/home) "/.config/Claude/claude_desktop_config.json"))))

(defn codex-config-path
  "Path to Codex config file."
  []
  (str (fs/home) "/.codex/config.toml"))

(defn read-json-file
  "Read and parse a JSON file. Returns {} if missing or invalid."
  [path]
  (if (fs/exists? path)
    (try (json/parse-string (slurp path))
         (catch Exception _ {}))
    {}))

(defn write-json-file
  "Write data as formatted JSON to a file."
  [path data]
  (fs/create-dirs (fs/parent path))
  (spit path (json/generate-string data {:pretty true}))
  (println (str "  Wrote: " path)))

(defn toml-escape
  "Escape a string for TOML output."
  [value]
  (-> value
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn toml-string
  "Render a TOML string value."
  [value]
  (str "\"" (toml-escape value) "\""))

(defn toml-array
  "Render a TOML array of strings."
  [values]
  (str "[" (str/join ", " (map toml-string values)) "]"))

(defn toml-inline-table
  "Render a TOML inline table from a string map."
  [m]
  (str "{ "
       (str/join ", "
                 (map (fn [[k v]]
                        (str (name k) " = " (toml-string v)))
                      m))
       " }"))

(defn remove-toml-block
  "Remove a TOML block by header (e.g., mcp_servers.miniforge-lsp)."
  [content header]
  (let [lines (str/split-lines (or content ""))
        header-line (str "[" header "]")]
    (loop [remaining lines
           acc []
           skipping? false]
      (if (empty? remaining)
        (str/join "\n" acc)
        (let [line (first remaining)
              trimmed (str/trim line)
              is-header (re-matches #"^\s*\[.+\]\s*$" line)]
          (cond
            (and (not skipping?) (= trimmed header-line))
            (recur (rest remaining) acc true)

            (and skipping? is-header)
            (recur remaining acc false)

            skipping?
            (recur (rest remaining) acc true)

            :else
            (recur (rest remaining) (conj acc line) false)))))))

(defn append-toml-block
  "Append a TOML block to existing content, ensuring separation."
  [content block]
  (let [content (str/trim (or content ""))]
    (str (when (seq content) (str content "\n\n"))
         block
         "\n")))

(defn render-codex-mcp-block
  "Render TOML block for Codex MCP server."
  [entry]
  (str "[mcp_servers.miniforge-lsp]\n"
       "command = " (toml-string (get entry "command")) "\n"
       "args = " (toml-array (get entry "args")) "\n"
       "cwd = " (toml-string (get entry "cwd")) "\n"
       "env = " (toml-inline-table (get entry "env")) "\n"))

(defn setup-claude-code
  "Write or update .mcp.json in the project root for Claude Code CLI."
  []
  (let [path (str (project-dir) "/.mcp.json")
        existing (read-json-file path)
        servers (get existing "mcpServers" {})
        updated (assoc existing
                       "mcpServers" (assoc servers
                                          "miniforge-lsp" (mcp-server-entry-claude-code)))]
    (write-json-file path updated)
    (println "  Claude Code configured. Restart Claude Code to pick up changes.")))

(defn setup-claude-desktop
  "Merge miniforge-lsp entry into Claude Desktop config."
  []
  (let [path (claude-desktop-config-path)]
    (if-not (fs/exists? path)
      (println (str "  Claude Desktop config not found at: " path
                    "\n  Is Claude Desktop installed?"))
      (let [existing (read-json-file path)
            servers (get existing "mcpServers" {})
            updated (assoc existing
                           "mcpServers" (assoc servers
                                              "miniforge-lsp" (mcp-server-entry-claude-desktop)))]
        (write-json-file path updated)
        (println "  Claude Desktop configured. Restart Claude Desktop to pick up changes.")))))

(defn setup-codex
  "Merge miniforge-lsp entry into Codex config (config.toml)."
  []
  (let [path (codex-config-path)
        entry (mcp-server-entry-claude-desktop)
        block (render-codex-mcp-block entry)
        existing (if (fs/exists? path) (slurp path) "")
        cleaned (remove-toml-block existing "mcp_servers.miniforge-lsp")
        updated (append-toml-block cleaned block)]
    (fs/create-dirs (fs/parent path))
    (spit path updated)
    (println (str "  Wrote: " path))
    (println "  Codex configured. Restart Codex to pick up changes.")))

(defn print-config-preview
  "Print what the MCP configs would look like, without writing anything."
  []
  (println "\nClaude Code CLI (.mcp.json):")
  (println (json/generate-string
            {"mcpServers" {"miniforge-lsp" (mcp-server-entry-claude-code)}}
            {:pretty true}))
  (println "\nClaude Desktop (claude_desktop_config.json):")
  (println (json/generate-string
            {"mcpServers" {"miniforge-lsp" (mcp-server-entry-claude-desktop)}}
            {:pretty true}))
  (println "\nCodex (config.toml):")
  (println (render-codex-mcp-block (mcp-server-entry-claude-desktop)))
  (println "\nRun with --claude-code, --claude-desktop, or --codex to write config files."))

(def ^:private client-registry
  {"--claude-code"    {:label "Claude Code"    :fn setup-claude-code}
   "--claude-desktop" {:label "Claude Desktop" :fn setup-claude-desktop}
   "--codex"          {:label "Codex"          :fn setup-codex}
   "--codex-cli"      {:label "Codex CLI"      :fn setup-codex}
   "--codex-desktop"  {:label "Codex Desktop"  :fn setup-codex}})

(defn setup
  "Generate MCP config for one or more clients.

   Arguments:
   - args - Client flags (--claude-code, --claude-desktop, --codex, --codex-cli,
     --codex-desktop) or empty for a preview of all configs"
  [args]
  (if (empty? args)
    (print-config-preview)
    (doseq [arg args]
      (if-let [{:keys [fn]} (get client-registry arg)]
        (fn)
        (println (str "  Unknown client: " arg
                      "\n  Available: " (str/join ", " (sort (keys client-registry)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (status)
  (install [])
  (install ["clojure"])
  (setup [])
  (setup ["--claude-code"])

  :leave-this-here)
