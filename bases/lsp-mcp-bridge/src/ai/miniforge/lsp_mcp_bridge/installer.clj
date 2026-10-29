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
(ns ai.miniforge.lsp-mcp-bridge.installer
  "Auto-installation of LSP server binaries.

   Checks if LSP binaries are available on PATH, and downloads/installs
   them to ~/.miniforge/bin/ if not found. A GitHub asset whose registry
   entry pins `:sha256` is verified before extraction. Every extraction
   step's result is checked, and the install fails unless the binary is
   on disk afterwards."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response])
  (:import
   (java.security MessageDigest)
   (java.util.zip GZIPInputStream)))

;------------------------------------------------------------------------------ Layer 0

;; Platform detection
(defn ^{:stratum 0} platform-key
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

(defn ^{:stratum 0} bin-dir
  "Get the miniforge binary directory."
  []
  (str (fs/home) "/.miniforge/bin"))

;; Binary resolution
(defn ^{:stratum 0} which
  "Find a binary on PATH. Returns the path or nil."
  [binary]
  (try
    (let [result (bp/sh ["which" binary])]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

;; Download and extraction
(defn ^{:stratum 0} download-file
  "Download a file from a URL to a local path.
   Returns nil on success or a canonical anomaly map on curl failure.
   `--fail` turns an HTTP error status into a non-zero exit; without it
   curl saved the error page as the archive and exited 0."
  [url dest-path]
  (binding [*out* *err*]
    (println "Downloading" url "..."))
  (let [result (bp/sh ["curl" "--fail" "-L" "-o" (str dest-path) "--progress-bar" url])]
    (when-not (zero? (:exit result))
      (response/make-anomaly :anomalies/fault
                             "Download failed"
                             {:lsp-installer/url    url
                              :lsp-installer/exit   (:exit result)
                              :lsp-installer/stderr (:err result)}))))

(defn ^{:stratum 0} extract-zip
  "Extract a zip archive to a directory."
  [archive-path dest-dir]
  (bp/sh ["unzip" "-o" (str archive-path) "-d" (str dest-dir)]))

(defn ^{:stratum 0} extract-tar-gz
  "Extract a tar.gz archive to a directory."
  [archive-path dest-dir]
  (bp/sh ["tar" "xzf" (str archive-path) "-C" (str dest-dir)]))

(defn ^{:stratum 0} extract-gzip
  "Decompress a single-file gzip archive to `dest-path` in-process
   (java.util.zip, no shell, so a space or quote in $HOME or TMPDIR is
   just part of the path). Writes beside the archive first and moves
   into place only after the whole stream decompressed, so a corrupt
   or truncated archive leaves nothing at `dest-path`. Throws on a
   corrupt stream. The raw file stream is its own with-open binding:
   the GZIPInputStream constructor reads the header and throws on a bad
   one, and the file must still be closed (the bridge is long-running)."
  [archive-path dest-path]
  (let [staged (str archive-path ".out")]
    (with-open [raw (io/input-stream (str archive-path))
                in  (GZIPInputStream. raw)
                out (io/output-stream staged)]
      (io/copy in out))
    (fs/move staged dest-path {:replace-existing true})))

(defn ^{:stratum 0} github-release-url
  "Build a GitHub Releases download URL."
  [github-repo version filename]
  (str "https://github.com/" github-repo "/releases/download/" version "/" filename))

;; Installation pipeline — GitHub releases
(defn ^{:stratum 0} failed? [state] (contains? state :failure))

(defn ^{:stratum 0} fail
  "Mark pipeline as failed with anomaly."
  [state message]
  (assoc state :failure (response/make-anomaly :anomalies/fault message)))

(defn ^{:stratum 0} sh-anomaly
  "Nil when a `bp/sh` result exited 0, else a fault anomaly naming
   `step` with its exit code and stderr. `bp/sh` does not throw on a
   non-zero exit, so each call whose failure matters goes through this."
  [step result]
  (when-not (zero? (:exit result))
    (response/make-anomaly :anomalies/fault
                           (str step " failed: " (str/trim (str (:err result))))
                           {:lsp-installer/step   step
                            :lsp-installer/exit   (:exit result)
                            :lsp-installer/stderr (:err result)})))

(defn ^{:stratum 0} file-sha256
  "Lower-case hex SHA-256 of the file at `path`."
  [path]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [^java.io.InputStream in (io/input-stream (str path))]
      (loop [n (.read in buf)]
        (when (pos? n)
          (.update md buf 0 (int n))
          (recur (.read in buf)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn ^{:stratum 0} fail-and-discard
  "Fail the pipeline with `anomaly` after removing the temp dir and
   whatever the install created at the final binary path, recursively
   (a symlink there is removed, not followed). A final path that existed
   before the download is left alone. A cleanup error is recorded on the
   anomaly as `:lsp-installer/cleanup-error` instead of replacing it.
   Plain try: this base declares no slingshot dep (rule 211 exemption 1)."
  [{:keys [temp-dir dest-dir binary final-preexisted?] :as state} anomaly]
  (let [cleanup-error (try
                        (when-not final-preexisted?
                          (fs/delete-tree (str dest-dir "/" binary)))
                        (some-> temp-dir fs/delete-tree)
                        nil
                        (catch Exception e e))]
    (assoc state :failure
           (cond-> anomaly
             cleanup-error (assoc :lsp-installer/cleanup-error (ex-message cleanup-error))))))

;; Installation orchestration
(defn ^{:stratum 0} find-server-entry
  "Find server entry in registry by tool-id."
  [registry tool-id]
  (->> (:servers registry)
       vals
       (filter #(= tool-id (:tool-id %)))
       first))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-binary
  "Find a binary, checking PATH first then ~/.miniforge/bin/.
   Returns the full path to the binary or nil. The local path counts
   only as a regular executable file: a directory or partial file left
   by a failed install must trigger a reinstall, not be launched."
  [binary]
  (or (which binary)
      (let [local-path (str (bin-dir) "/" binary)]
        (when (and (fs/regular-file? local-path) (fs/executable? local-path))
          local-path))))

(defn ^{:stratum 1} extract-archive
  "Extract an archive based on its type. Returns nil on success or an
   anomaly when unzip or tar exits non-zero; a corrupt gzip throws.
   step-place-binary checks the result is on disk and makes it executable."
  [archive-path dest-dir extract-type binary-name]
  (let [dest (str dest-dir "/" binary-name)]
    (case extract-type
      :zip    (sh-anomaly "unzip" (extract-zip archive-path dest-dir))
      :tar-gz (sh-anomaly "tar" (extract-tar-gz archive-path dest-dir))
      :gzip   (do (extract-gzip archive-path dest) nil)
      :none   (do (fs/copy archive-path dest {:replace-existing true}) nil))))

(defn ^{:stratum 1} step-resolve-asset
  "Resolve the platform-specific asset from the server entry."
  [state]
  (if (failed? state) state
      (let [{:keys [server-entry platform]} state
            {:keys [assets]} server-entry
            platform-asset (get assets platform)]
        (if-not platform-asset
          (fail state (str "No binary available for platform: " platform))
          (assoc state :platform-asset platform-asset)))))

(defn ^{:stratum 1} step-download
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
          (if-let [download-anomaly (download-file url archive-path)]
            (do
              (fs/delete-tree temp-dir)
              (assoc state :failure download-anomaly))
            (assoc state
                   :url url :temp-dir temp-dir :archive-path archive-path
                   :dest-dir dest-dir :binary binary
                   :final-preexisted? (fs/exists? (str dest-dir "/" binary)
                                                  {:nofollow-links true})))
          (catch Exception e
            (fs/delete-tree temp-dir)
            (fail state (str "Download failed: " (.getMessage e))))))))

(defn ^{:stratum 1} step-verify-checksum
  "Check the downloaded archive against the asset's `:sha256` when the
   registry pins one; a mismatch fails the install before extraction.
   Without a pinned hash the archive is used unverified, said on stderr."
  [state]
  (if (failed? state) state
      (let [{:keys [platform-asset archive-path]} state
            expected (some-> (:sha256 platform-asset) str/lower-case)
            actual   (when expected (file-sha256 archive-path))]
        (cond
          (nil? expected)
          (do (binding [*out* *err*]
                (println "No sha256 pinned for" (:file platform-asset) "- installing unverified"))
              state)

          (= expected actual)
          state

          :else
          (fail-and-discard state
                            (response/make-anomaly :anomalies/fault
                                                   (str "Checksum mismatch for " (:file platform-asset))
                                                   {:lsp-installer/expected-sha256 expected
                                                    :lsp-installer/actual-sha256   actual}))))))

(defn ^{:stratum 1} step-place-binary
  "Move a nested binary into the bin dir, check it is there, and make it
   executable. Fails the install when the binary is missing after
   extraction or chmod exits non-zero."
  [state]
  (if (failed? state) state
      (let [{:keys [dest-dir platform-asset binary temp-dir]} state
            final-binary (str dest-dir "/" binary)]
        (try
          (when-let [nested (:binary-in-archive platform-asset)]
            (let [nested-path (str dest-dir "/" nested)]
              (when (and (not= nested-path final-binary) (fs/exists? nested-path))
                (fs/move nested-path final-binary {:replace-existing true}))))
          (if-let [anomaly (if (fs/regular-file? final-binary)
                             (sh-anomaly "chmod" (bp/sh ["chmod" "+x" final-binary]))
                             (response/make-anomaly
                              :anomalies/fault
                              (str "Binary not found after extraction (no regular file at "
                                   final-binary ")")))]
            (fail-and-discard state anomaly)
            (do (fs/delete-tree temp-dir)
                (assoc state :installed final-binary)))
          (catch Exception e
            (fail-and-discard state (response/make-anomaly
                                     :anomalies/fault
                                     (str "Binary placement failed: " (.getMessage e)))))))))

(defn ^{:stratum 1} install-pipeline->result
  "Convert pipeline state to result."
  [state]
  (if (failed? state)
    (:failure state)
    {:installed (:installed state)}))

(defn ^{:stratum 1} install-via-npm
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

(defn ^{:stratum 1} install-via-go
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} step-extract
  "Extract the archive into the bin dir. An extractor anomaly or throw
   (corrupt archive) fails the install and discards the partial install."
  [state]
  (if (failed? state) state
      (let [{:keys [archive-path dest-dir platform-asset binary]} state]
        (try
          (if-let [anomaly (extract-archive archive-path dest-dir (:extract platform-asset) binary)]
            (fail-and-discard state anomaly)
            state)
          (catch Exception e
            (fail-and-discard state (response/make-anomaly
                                     :anomalies/fault
                                     (str "Extraction failed: " (.getMessage e)))))))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} install-via-github
  "Install an LSP binary from GitHub Releases."
  [server-entry platform]
  (-> {:server-entry server-entry :platform platform}
      step-resolve-asset
      step-download
      step-verify-checksum
      step-extract
      step-place-binary
      install-pipeline->result))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} run-install
  "Run the appropriate install method for a server entry."
  [server-entry]
  (let [install-method (get server-entry :install-method :github)]
    (case install-method
      :github     (install-via-github server-entry (platform-key))
      :npm        (install-via-npm server-entry)
      :go-install (install-via-go server-entry)
      :custom     (response/make-anomaly
                   :anomalies/unsupported
                   (str "Manual installation required. "
                        (:install-instructions server-entry))))))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} ensure-installed
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
