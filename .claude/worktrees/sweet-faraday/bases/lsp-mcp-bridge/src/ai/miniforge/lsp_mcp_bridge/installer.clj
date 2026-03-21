(ns ai.miniforge.lsp-mcp-bridge.installer
  "Auto-installation of LSP server binaries.

   Checks if LSP binaries are available on PATH, and downloads/installs
   them to ~/.miniforge/bin/ if not found.

   Layer 0: Platform detection
   Layer 1: Binary resolution
   Layer 2: Download and extraction
   Layer 3: Installation pipeline
   Layer 4: Installation orchestration"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response]))

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

(defn download-file
  "Download a file from a URL to a local path."
  [url dest-path]
  (binding [*out* *err*]
    (println "Downloading" url "..."))
  (let [result (bp/sh ["curl" "-L" "-o" (str dest-path) "--progress-bar" url])]
    (when-not (zero? (:exit result))
      (throw (ex-info "Download failed" {:url url :exit (:exit result)})))))

(defn extract-zip
  "Extract a zip archive to a directory."
  [archive-path dest-dir]
  (bp/sh ["unzip" "-o" (str archive-path) "-d" (str dest-dir)]))

(defn extract-tar-gz
  "Extract a tar.gz archive to a directory."
  [archive-path dest-dir]
  (bp/sh ["tar" "xzf" (str archive-path) "-C" (str dest-dir)]))

(defn extract-gzip
  "Extract a gzip file (single file, not tar)."
  [archive-path dest-path]
  (bp/sh ["sh" "-c" (str "gunzip -c " archive-path " > " dest-path)]))

(defn extract-archive
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

(defn github-release-url
  "Build a GitHub Releases download URL."
  [github-repo version filename]
  (str "https://github.com/" github-repo "/releases/download/" version "/" filename))

;------------------------------------------------------------------------------ Layer 3
;; Installation pipeline — GitHub releases

(defn failed? [state] (contains? state :failure))

(defn fail
  "Mark pipeline as failed with anomaly."
  [state message]
  (assoc state :failure (response/make-anomaly :anomalies/fault message)))

(defn step-resolve-asset
  "Resolve the platform-specific asset from the server entry."
  [state]
  (if (failed? state) state
      (let [{:keys [server-entry platform]} state
            {:keys [assets]} server-entry
            platform-asset (get assets platform)]
        (if-not platform-asset
          (fail state (str "No binary available for platform: " platform))
          (assoc state :platform-asset platform-asset)))))

(defn step-download
  "Download the asset archive to a temp directory."
  [state]
  (if (failed? state) state
      (let [{:keys [server-entry platform-asset]} state
            {:keys [github version binary]} server-entry
            url (github-release-url github version (:file platform-asset))
            temp-dir (str (fs/create-temp-dir {:prefix "miniforge-install-"}))
            archive-path (str temp-dir "/" (:file platform-asset))
            dest-dir (bin-dir)]
        (try
          (fs/create-dirs dest-dir)
          (download-file url archive-path)
          (assoc state
                 :url url :temp-dir temp-dir :archive-path archive-path
                 :dest-dir dest-dir :binary binary)
          (catch Exception e
            (fs/delete-tree temp-dir)
            (fail state (str "Download failed: " (.getMessage e))))))))

(defn step-extract
  "Extract the archive and place binary."
  [state]
  (if (failed? state) state
      (let [{:keys [archive-path dest-dir platform-asset binary]} state]
        (try
          (extract-archive archive-path dest-dir (:extract platform-asset) binary)
          state
          (catch Exception e
            (fail state (str "Extraction failed: " (.getMessage e))))))))

(defn step-place-binary
  "Handle nested binary location and set permissions."
  [state]
  (if (failed? state) state
      (let [{:keys [dest-dir platform-asset binary temp-dir]} state]
        (try
          (when-let [nested (:binary-in-archive platform-asset)]
            (let [nested-path (str dest-dir "/" nested)
                  final-path (str dest-dir "/" binary)]
              (when (and (not= nested-path final-path) (fs/exists? nested-path))
                (fs/move nested-path final-path {:replace-existing true}))))
          (let [final-binary (str dest-dir "/" binary)]
            (bp/sh ["chmod" "+x" final-binary])
            (fs/delete-tree temp-dir)
            (assoc state :installed final-binary))
          (catch Exception e
            (fs/delete-tree temp-dir)
            (fail state (str "Binary placement failed: " (.getMessage e))))))))

(defn install-pipeline->result
  "Convert pipeline state to result."
  [state]
  (if (failed? state)
    (:failure state)
    {:installed (:installed state)}))

(defn install-via-github
  "Install an LSP binary from GitHub Releases."
  [server-entry platform]
  (-> {:server-entry server-entry :platform platform}
      step-resolve-asset
      step-download
      step-extract
      step-place-binary
      install-pipeline->result))

(defn install-via-npm
  "Install an LSP binary via npm."
  [server-entry]
  (let [{:keys [npm-package also-requires binary]} server-entry
        packages (cons npm-package (or also-requires []))]
    (binding [*out* *err*]
      (println "Installing via npm:" (str/join " " packages)))
    (let [result (bp/sh (into ["npm" "install" "-g"] packages))]
      (if (zero? (:exit result))
        {:installed (which binary)}
        (response/make-anomaly :anomalies/fault
                               (str "npm install failed: " (:err result)))))))

(defn install-via-go
  "Install an LSP binary via go install."
  [server-entry]
  (let [{:keys [go-package binary]} server-entry]
    (binding [*out* *err*]
      (println "Installing via go install:" go-package))
    (let [result (bp/sh ["go" "install" go-package])]
      (if (zero? (:exit result))
        {:installed (which binary)}
        (response/make-anomaly :anomalies/fault
                               (str "go install failed: " (:err result)))))))

;------------------------------------------------------------------------------ Layer 4
;; Installation orchestration

(defn find-server-entry
  "Find server entry in registry by tool-id."
  [registry tool-id]
  (->> (:servers registry)
       vals
       (filter #(= tool-id (:tool-id %)))
       first))

(defn run-install
  "Run the appropriate install method for a server entry."
  [server-entry]
  (let [install-method (or (:install-method server-entry) :github)]
    (case install-method
      :github     (install-via-github server-entry (platform-key))
      :npm        (install-via-npm server-entry)
      :go-install (install-via-go server-entry)
      :custom     (response/make-anomaly
                   :anomalies/unsupported
                   (str "Manual installation required. "
                        (:install-instructions server-entry))))))

(defn ensure-installed
  "Ensure an LSP binary is installed. Downloads if not found on PATH.

   Arguments:
   - registry     - LSP registry data (from lsp-registry.edn)
   - tool-id      - Tool identifier keyword (e.g. :lsp/clojure)
   - command      - Original command vector (e.g. [\"clojure-lsp\"])

   Returns:
   - {:command updated-command} on success
   - Anomaly map on failure"
  [registry tool-id command]
  (let [binary (first command)]
    (if (resolve-binary binary)
      {:command command}
      (let [server-entry (find-server-entry registry tool-id)]
        (if-not server-entry
          (response/make-anomaly
           :anomalies/not-found
           (str "LSP binary not found and no install info for: " binary
                ". Install it manually and ensure it's on your PATH."))
          (let [result (run-install server-entry)]
            (if (response/anomaly-map? result)
              result
              (if-let [installed-path (:installed result)]
                {:command (assoc command 0 installed-path)}
                (response/make-anomaly :anomalies/fault
                                       "Installation succeeded but binary not found")))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (platform-key)        ;; => "macos-aarch64"
  (which "clojure-lsp") ;; => "/opt/homebrew/bin/clojure-lsp"
  (resolve-binary "clojure-lsp")
  (bin-dir)             ;; => "/Users/chris/.miniforge/bin"

  :leave-this-here)
