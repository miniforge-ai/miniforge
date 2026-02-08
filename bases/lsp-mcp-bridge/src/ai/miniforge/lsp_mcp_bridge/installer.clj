(ns ai.miniforge.lsp-mcp-bridge.installer
  "Auto-installation of LSP server binaries.

   Checks if LSP binaries are available on PATH, and downloads/installs
   them to ~/.miniforge/bin/ if not found.

   Layer 0: Platform detection
   Layer 1: Binary resolution
   Layer 2: Download and extraction
   Layer 3: Installation orchestration"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Platform detection

(defn platform-key
  "Detect the current platform. Returns a string like 'macos-aarch64'."
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        os-arch (System/getProperty "os.arch")
        os (cond
             (str/includes? os-name "mac") "macos"
             (str/includes? os-name "linux") "linux"
             (str/includes? os-name "win") "windows"
             :else "unknown")
        arch (case os-arch
               "aarch64" "aarch64"
               "arm64"   "aarch64"
               "amd64"   "x86_64"
               "x86_64"  "x86_64"
               os-arch)]
    (str os "-" arch)))

(defn bin-dir
  "Get the miniforge binary directory."
  []
  (str (fs/home) "/.miniforge/bin"))

;------------------------------------------------------------------------------ Layer 1
;; Binary resolution

(defn which
  "Find a binary on PATH. Returns the path or nil."
  [binary]
  (try
    (let [result (bp/sh ["which" binary])]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

(defn resolve-binary
  "Find a binary, checking PATH first then ~/.miniforge/bin/.
   Returns the full path to the binary or nil."
  [binary]
  (or (which binary)
      (let [local-path (str (bin-dir) "/" binary)]
        (when (fs/exists? local-path)
          local-path))))

;------------------------------------------------------------------------------ Layer 2
;; Download and extraction

(defn- download-file
  "Download a file from a URL to a local path."
  [url dest-path]
  (binding [*out* *err*]
    (println "Downloading" url "..."))
  (let [result (bp/sh ["curl" "-L" "-o" (str dest-path) "--progress-bar" url])]
    (when-not (zero? (:exit result))
      (throw (ex-info "Download failed" {:url url :exit (:exit result)})))))

(defn- extract-zip
  "Extract a zip archive to a directory."
  [archive-path dest-dir]
  (bp/sh ["unzip" "-o" (str archive-path) "-d" (str dest-dir)]))

(defn- extract-tar-gz
  "Extract a tar.gz archive to a directory."
  [archive-path dest-dir]
  (bp/sh ["tar" "xzf" (str archive-path) "-C" (str dest-dir)]))

(defn- extract-gzip
  "Extract a gzip file (single file, not tar)."
  [archive-path dest-path]
  (bp/sh ["sh" "-c" (str "gunzip -c " (str archive-path) " > " (str dest-path))]))

(defn- extract-archive
  "Extract an archive based on its type."
  [archive-path dest-dir extract-type binary-name]
  (case extract-type
    :zip (extract-zip archive-path dest-dir)
    :tar-gz (extract-tar-gz archive-path dest-dir)
    :gzip (let [dest (str dest-dir "/" binary-name)]
            (extract-gzip archive-path dest)
            (bp/sh ["chmod" "+x" dest]))
    :none (let [dest (str dest-dir "/" binary-name)]
            (fs/copy archive-path dest {:replace-existing true})
            (bp/sh ["chmod" "+x" dest]))))

(defn- github-release-url
  "Build a GitHub Releases download URL."
  [github-repo version filename]
  (str "https://github.com/" github-repo "/releases/download/" version "/" filename))

;------------------------------------------------------------------------------ Layer 3
;; Installation orchestration

(defn- install-via-github
  "Install an LSP binary from GitHub Releases."
  [server-entry platform]
  (let [{:keys [github version assets binary]} server-entry
        platform-asset (get assets platform)]
    (if-not platform-asset
      {:error (str "No binary available for platform: " platform)}
      (let [url (github-release-url github version (:file platform-asset))
            dest-dir (bin-dir)
            temp-dir (str (fs/create-temp-dir {:prefix "miniforge-install-"}))
            archive-path (str temp-dir "/" (:file platform-asset))]
        (try
          (fs/create-dirs dest-dir)
          (download-file url archive-path)
          (extract-archive archive-path dest-dir (:extract platform-asset) binary)
          ;; Handle binary-in-archive (nested binary location)
          (when-let [nested (:binary-in-archive platform-asset)]
            (let [nested-path (str dest-dir "/" nested)
                  final-path (str dest-dir "/" binary)]
              (when (and (not= nested-path final-path) (fs/exists? nested-path))
                (fs/move nested-path final-path {:replace-existing true}))))
          (let [final-binary (str dest-dir "/" binary)]
            (bp/sh ["chmod" "+x" final-binary])
            {:installed final-binary})
          (finally
            (fs/delete-tree temp-dir)))))))

(defn- install-via-npm
  "Install an LSP binary via npm."
  [server-entry]
  (let [{:keys [npm-package also-requires]} server-entry
        packages (cons npm-package (or also-requires []))]
    (binding [*out* *err*]
      (println "Installing via npm:" (str/join " " packages)))
    (let [result (bp/sh (into ["npm" "install" "-g"] packages))]
      (if (zero? (:exit result))
        {:installed (which (:binary server-entry))}
        {:error (str "npm install failed: " (:err result))}))))

(defn- install-via-go
  "Install an LSP binary via go install."
  [server-entry]
  (let [{:keys [go-package]} server-entry]
    (binding [*out* *err*]
      (println "Installing via go install:" go-package))
    (let [result (bp/sh ["go" "install" go-package])]
      (if (zero? (:exit result))
        {:installed (which (:binary server-entry))}
        {:error (str "go install failed: " (:err result))}))))

(defn ensure-installed
  "Ensure an LSP binary is installed. Downloads if not found on PATH.

   Arguments:
   - registry     - LSP registry data (from lsp-registry.edn)
   - tool-id      - Tool identifier keyword (e.g. :lsp/clojure)
   - command      - Original command vector (e.g. [\"clojure-lsp\"])

   Returns:
   - {:command updated-command} on success
   - {:error message} on failure"
  [registry tool-id command]
  (let [binary (first command)]
    (if (resolve-binary binary)
      ;; Already installed
      {:command command}
      ;; Need to install
      (let [;; Find in registry by tool-id
            server-entry (->> (:servers registry)
                              vals
                              (filter #(= tool-id (:tool-id %)))
                              first)]
        (if-not server-entry
          {:error (str "LSP binary not found and no install info for: " binary
                       ". Install it manually and ensure it's on your PATH.")}
          (let [install-method (or (:install-method server-entry) :github)
                result (case install-method
                         :github (install-via-github server-entry (platform-key))
                         :npm (install-via-npm server-entry)
                         :go-install (install-via-go server-entry)
                         :custom {:error (str "Manual installation required. "
                                              (:install-instructions server-entry))})]
            (if (:error result)
              result
              ;; Update command to use the installed binary path
              (let [installed-path (:installed result)]
                (if installed-path
                  {:command (assoc command 0 installed-path)}
                  {:error "Installation succeeded but binary not found"})))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (platform-key)        ;; => "macos-aarch64"
  (which "clojure-lsp") ;; => "/opt/homebrew/bin/clojure-lsp"
  (resolve-binary "clojure-lsp")
  (bin-dir)             ;; => "/Users/chris/.miniforge/bin"

  :leave-this-here)
