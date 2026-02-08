(ns ai.miniforge.lsp-mcp-bridge.tasks
  "bb task implementations for LSP server management.

   Provides CLI commands for installing LSP servers and configuring
   Claude Code / Claude Desktop to use the miniforge LSP-MCP bridge.

   Layer 0: Name resolution and display helpers
   Layer 1: lsp:status — show installed/available LSP servers
   Layer 2: lsp:install — install LSP servers
   Layer 3: lsp:setup — generate MCP config for Claude Code / Claude Desktop"
  (:require
   [ai.miniforge.lsp-mcp-bridge.config :as config]
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]
   [ai.miniforge.response.interface :as response]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Name resolution and display helpers

(defn- build-name-index
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

(defn- resolve-server-names
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

(defn- all-server-entries
  "Get all server entries from the registry."
  [registry]
  (map (fn [[k v]] {:server-key k :entry v})
       (:servers registry)))

(defn- method-label
  "Human-readable label for an install method."
  [entry]
  (case (or (:install-method entry) :github)
    :github     "github release"
    :npm        "npm"
    :go-install "go install"
    :custom     "manual install"
    (name (or (:install-method entry) :github))))

(defn- pad
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
;; lsp:setup — generate MCP config for Claude Code / Claude Desktop

(defn- project-dir
  "Get the absolute project directory."
  []
  (System/getProperty "user.dir"))

(defn- mcp-server-entry-claude-code
  "Build MCP server config for Claude Code CLI (.mcp.json)."
  []
  {"command" "bb"
   "args"    ["lsp-mcp-bridge"]
   "env"     {"MINIFORGE_PROJECT_DIR" "."}})

(defn- mcp-server-entry-claude-desktop
  "Build MCP server config for Claude Desktop."
  []
  (let [dir (project-dir)]
    {"command" "bb"
     "args"    ["--file" (str dir "/bb.edn") "lsp-mcp-bridge"]
     "cwd"     dir
     "env"     {"MINIFORGE_PROJECT_DIR" dir}}))

(defn- claude-desktop-config-path
  "Path to Claude Desktop config file."
  []
  (let [platform (installer/platform-key)]
    (if (str/starts-with? platform "macos")
      (str (fs/home) "/Library/Application Support/Claude/claude_desktop_config.json")
      (str (fs/home) "/.config/Claude/claude_desktop_config.json"))))

(defn- read-json-file
  "Read and parse a JSON file. Returns {} if missing or invalid."
  [path]
  (if (fs/exists? path)
    (try (json/parse-string (slurp path))
         (catch Exception _ {}))
    {}))

(defn- write-json-file
  "Write data as formatted JSON to a file."
  [path data]
  (spit path (json/generate-string data {:pretty true}))
  (println (str "  Wrote: " path)))

(defn- setup-claude-code
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

(defn- setup-claude-desktop
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

(defn- print-config-preview
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
  (println "\nRun with --claude-code or --claude-desktop to write config files."))

(defn setup
  "Generate MCP config for Claude Code and/or Claude Desktop.

   Arguments:
   - args - Command-line args (--claude-code, --claude-desktop, or empty for preview)"
  [args]
  (let [args-set (set args)]
    (cond
      (contains? args-set "--claude-code")
      (setup-claude-code)

      (contains? args-set "--claude-desktop")
      (setup-claude-desktop)

      :else
      (print-config-preview))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (status)
  (install [])
  (install ["clojure"])
  (setup [])
  (setup ["--claude-code"])

  :leave-this-here)
