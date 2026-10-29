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
   (java.nio.channels FileChannel)
   (java.nio.file CopyOption Files OpenOption StandardCopyOption StandardOpenOption)
   (java.security MessageDigest)
   (java.util.concurrent Semaphore)
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
  "Fail the pipeline with `anomaly` after deleting the staging dir
   (delete-tree removes symlinks, it does not follow them). Nothing is
   written to the install target before the final move, so there is
   nothing else to undo. A cleanup error is recorded on the anomaly as
   `:lsp-installer/cleanup-error` instead of replacing it.
   Plain try: this base declares no slingshot dep (rule 211 exemption 1)."
  [{:keys [temp-dir] :as state} anomaly]
  (let [cleanup-error (try
                        (some-> temp-dir fs/delete-tree)
                        nil
                        (catch Exception e e))]
    (assoc state :failure
           (cond-> anomaly
             cleanup-error (assoc :lsp-installer/cleanup-error (ex-message cleanup-error))))))

(defn ^{:stratum 0} install-dist!
  "Keep a whole extracted archive (LuaLS loads main.lua and script/ from
   beside its binary): rename the staging `extract/` dir to a new
   `.dist/<binary>/<version>-<staging name>/` in the bin dir, then swap a
   launcher over `final-binary` that execs the binary by its real path.
   Not a symlink: LuaLS on macOS locates itself with _NSGetExecutablePath,
   which does not resolve links. Both renames stay on one filesystem; the
   swap is atomic. If the swap fails the new dist dir is removed, so the
   existing install is untouched. Returns the new dist dir.
   Plain try: this base declares no slingshot dep (rule 211 exemption 1)."
  [{:keys [dest-dir extract-dir temp-dir binary server-entry platform-asset]} final-binary]
  (let [dist-dir (fs/path dest-dir ".dist" binary
                          (str (:version server-entry) "-" (fs/file-name temp-dir)))
        launcher (fs/path temp-dir "launcher")]
    (fs/create-dirs (fs/parent dist-dir))
    (Files/move (fs/path extract-dir) dist-dir
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (try
      (let [real (str (fs/real-path (fs/path dist-dir (get platform-asset :binary-in-archive binary))))]
        (spit (str launcher) (str "#!/bin/sh\nexec '" (str/replace real "'" "'\\''") "' \"$@\"\n")))
      (fs/set-posix-file-permissions launcher "rwxr-xr-x")
      (Files/move launcher (fs/path final-binary)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      dist-dir
      (catch Exception e
        (fs/delete-tree dist-dir)
        (throw e)))))

(defn ^{:stratum 0} finish-install!
  "Cleanup once the target has been replaced, so the install has already
   succeeded. For an archive install, record `dist` in
   `.dist/<binary>/active` (atomic rename) and prune that binary's trees
   other than `dist` and the one `active` named before: the previous
   install, which a running server may still use. A tree left by an
   interrupted install is never recorded, so it is pruned. The prune is
   skipped if `active` cannot be read or written. Then delete the staging
   dir. Returns warning strings instead of throwing.
   Plain try: this base declares no slingshot dep (rule 211 exemption 1)."
  [temp-dir dist]
  (let [delete! (fn [path]
                  (try (fs/delete-tree path) nil
                       (catch Exception e (str "could not remove " path ": " (ex-message e)))))
        root    (some-> dist fs/parent)
        active  (some-> root (fs/path "active"))
        record! (fn []
                  (let [prev (when (fs/exists? active) (str/trim (slurp (str active))))
                        tmp  (fs/path temp-dir "active")]
                    (spit (str tmp) (str (fs/file-name dist)))
                    (Files/move tmp active (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                                   StandardCopyOption/REPLACE_EXISTING]))
                    #{(str (fs/file-name dist)) prev}))]
    (filterv some?
             (conj (if dist
                     (try (let [kept (record!)]
                            (into [] (keep delete!)
                                  (->> (fs/list-dir root)
                                       (filter #(fs/directory? % {:nofollow-links true}))
                                       (remove #(kept (str (fs/file-name %)))))))
                          (catch Exception e
                            [(str "could not record or prune " root ": " (ex-message e))]))
                     [])
                   (delete! temp-dir)))))

(defn ^{:stratum 0} contained-regular-file?
  "True when `rel` names a regular file under `root`, reached with no
   symlink at any path component, whose real path stays under `root`.
   `:nofollow-links` alone checks only the last component: an archive
   holding `bin -> ../..` would make `bin/tool` the live install target."
  [root rel]
  (let [steps (reductions fs/path (fs/path root) (fs/components (fs/path rel)))
        file  (last steps)]
    (and (not-any? fs/sym-link? (rest steps))
         (fs/regular-file? file {:nofollow-links true})
         (str/starts-with? (str (fs/real-path file)) (str (fs/real-path root) "/")))))

;; A FileChannel lock belongs to the whole JVM: a second thread asking for it
;; gets OverlappingFileLockException instead of waiting. Installers in this
;; process queue on a per-binary semaphore first.
(defonce ^{:stratum 0} ^:private install-semaphores (atom {}))

(def ^{:stratum 0} ^:private install-lock-wait-ms
  "How long an installer waits for another process's install of the same
   binary (a download included) before giving up."
  (* 10 60 1000))

;; Installation orchestration
(defn ^{:stratum 0} find-server-entry
  "Find server entry in registry by tool-id."
  [registry tool-id]
  (->> (:servers registry)
       vals
       (filter #(= tool-id (:tool-id %)))
       first))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-install-lock
  "`(thunk contended?)` while holding `binary`'s install lock: a per-binary
   semaphore in this process, then a FileChannel lock on
   `<dest-dir>/.dist/<binary>/.lock` across processes, so one install and
   its prune run at a time. `contended?` is true when another installer
   held either lock first; the caller then rechecks the target. An anomaly
   when the file lock stays busy past `install-lock-wait-ms` or locking
   fails. FileLock is hinted AutoCloseable: babashka does not expose it.
   Plain try: this base declares no slingshot dep (rule 211 exemption 1)."
  [dest-dir binary thunk]
  (let [^Semaphore sem (get (swap! install-semaphores update binary #(or % (Semaphore. 1))) binary)
        waited?        (not (.tryAcquire sem))
        lock-file      (fs/path dest-dir ".dist" binary ".lock")]
    (when waited? (.acquire sem))
    (try
      (fs/create-dirs (fs/parent lock-file))
      (with-open [channel (FileChannel/open lock-file (into-array OpenOption [StandardOpenOption/CREATE
                                                                              StandardOpenOption/WRITE]))]
        (let [first-try (.tryLock channel)
              deadline  (+ (System/currentTimeMillis) install-lock-wait-ms)
              ^java.lang.AutoCloseable held (or first-try
                                                (loop []
                                                  (or (.tryLock channel)
                                                      (when (< (System/currentTimeMillis) deadline)
                                                        (Thread/sleep 100)
                                                        (recur)))))]
          (if held
            (try (thunk (or waited? (nil? first-try)))
                 (finally (.close held)))
            (response/make-anomaly :anomalies/unavailable
                                   (str "Another install of " binary " holds " lock-file)
                                   {:lsp-installer/lock-file (str lock-file)}))))
      (catch Exception e
        (response/make-anomaly :anomalies/fault
                               (str "Install of " binary " failed: " (ex-message e))))
      (finally (.release sem)))))

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

(defn ^{:stratum 1} step-stage
  "Create a fresh staging dir inside the bin dir: the same filesystem as
   the install target, so the finished binary can be renamed over it
   atomically. Failing to create it fails the install as an anomaly."
  [state]
  (if (failed? state) state
      (try
        (let [dest-dir (bin-dir)]
          (assoc state
                 :dest-dir dest-dir
                 :temp-dir (str (fs/create-temp-dir {:dir    (fs/create-dirs dest-dir)
                                                     :prefix ".install-"}))))
        (catch Exception e
          (fail state (str "Could not create staging dir: " (.getMessage e)))))))

(defn ^{:stratum 1} step-download
  "Download the asset archive into the staging dir."
  [state]
  (if (failed? state) state
      (let [{:keys [server-entry platform-asset temp-dir]} state
            {:keys [github version binary]} server-entry
            url (github-release-url github version (:file platform-asset))
            archive-path (str temp-dir "/" (:file platform-asset))]
        (try
          (if-let [download-anomaly (download-file url archive-path)]
            (fail-and-discard state download-anomaly)
            (assoc state :url url :archive-path archive-path :binary binary))
          (catch Exception e
            (fail-and-discard state (response/make-anomaly
                                     :anomalies/fault
                                     (str "Download failed: " (.getMessage e)))))))))

(defn ^{:stratum 1} step-verify-checksum
  "Check the downloaded archive against the asset's `:sha256` when the
   registry pins one; a mismatch fails the install before extraction.
   Without a pinned hash the archive is used unverified, said on stderr.
   An error while hashing fails the install and deletes the staging dir."
  [state]
  (if (failed? state) state
      (let [{:keys [platform-asset archive-path]} state
            expected (some-> (:sha256 platform-asset) str/lower-case)
            actual   (try (when expected (file-sha256 archive-path))
                          (catch Exception e e))]
        (cond
          (instance? Exception actual)
          (fail-and-discard state (response/make-anomaly
                                   :anomalies/fault
                                   (str "Checksum failed: " (ex-message actual))))

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
  "Check the extracted binary is a regular file inside the staging tree
   (no symlink anywhere on its path) and chmod it there. A zip or tar.gz install
   keeps the whole archive via install-dist!; a single-file asset is
   renamed over the target in one atomic move (Files/move, which refuses
   a directory target rather than moving into it as fs/move would). On
   any failure before that move the staging dir is deleted and the target
   is untouched; after it, cleanup errors are warnings on a success."
  [state]
  (if (failed? state) state
      (let [{:keys [dest-dir extract-dir platform-asset binary temp-dir]} state
            in-archive   (get platform-asset :binary-in-archive binary)
            staged       (str extract-dir "/" in-archive)
            final-binary (str dest-dir "/" binary)]
        (try
          (if-let [anomaly (if (contained-regular-file? extract-dir in-archive)
                             (sh-anomaly "chmod" (bp/sh ["chmod" "+x" staged]))
                             (response/make-anomaly
                              :anomalies/fault
                              (str "Binary not found in the archive (no regular file at "
                                   in-archive ")")))]
            (fail-and-discard state anomaly)
            (let [dist     (if (#{:zip :tar-gz} (:extract platform-asset))
                             (install-dist! state final-binary)
                             (do (Files/move (fs/path staged) (fs/path final-binary)
                                             (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                                     StandardCopyOption/REPLACE_EXISTING]))
                                 nil))
                  ;; The target is replaced: the install has succeeded, and
                  ;; cleanup problems from here on are warnings.
                  warnings (finish-install! temp-dir dist)]
              (binding [*out* *err*]
                (doseq [w warnings] (println "Warning:" w)))
              (cond-> (assoc state :installed final-binary)
                (seq warnings) (assoc :warnings warnings))))
          (catch Exception e
            (fail-and-discard state (response/make-anomaly
                                     :anomalies/fault
                                     (str "Binary placement failed: " (.getMessage e)))))))))

(defn ^{:stratum 1} install-pipeline->result
  "Convert pipeline state to result. Cleanup warnings from a successful
   install ride along as `:lsp-installer/warnings`."
  [state]
  (if (failed? state)
    (:failure state)
    (cond-> {:installed (:installed state)}
      (seq (:warnings state)) (assoc :lsp-installer/warnings (:warnings state)))))

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
  "Extract the archive into the staging dir's `extract/`, never into the
   bin dir. An extractor anomaly or throw (corrupt archive) fails the
   install and deletes the staging dir."
  [state]
  (if (failed? state) state
      (let [{:keys [archive-path temp-dir platform-asset binary]} state
            extract-dir (str temp-dir "/extract")]
        (try
          (fs/create-dirs extract-dir)
          (if-let [anomaly (extract-archive archive-path extract-dir (:extract platform-asset) binary)]
            (fail-and-discard state anomaly)
            (assoc state :extract-dir extract-dir))
          (catch Exception e
            (fail-and-discard state (response/make-anomaly
                                     :anomalies/fault
                                     (str "Extraction failed: " (.getMessage e)))))))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} install-via-github
  "Install an LSP binary from GitHub Releases under the binary's install
   lock, so concurrent installers cannot replace or prune each other's
   work. One that had to wait rechecks the target first and uses it when
   the installer it waited for left a regular executable file there."
  [server-entry platform]
  (let [dest-dir (bin-dir)
        binary   (:binary server-entry)
        target   (str dest-dir "/" binary)]
    (call-with-install-lock
     dest-dir binary
     (fn [contended?]
       (if (and contended? (fs/regular-file? target) (fs/executable? target))
         {:installed target}
         (-> {:server-entry server-entry :platform platform}
             step-resolve-asset
             step-stage
             step-download
             step-verify-checksum
             step-extract
             step-place-binary
             install-pipeline->result))))))

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
   - {:command updated-command} on success, plus :lsp-installer/warnings
     when the install succeeded but its cleanup did not
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
                (cond-> {:command (assoc command 0 installed-path)}
                  (:lsp-installer/warnings result)
                  (assoc :lsp-installer/warnings (:lsp-installer/warnings result)))
                (response/make-anomaly :anomalies/fault
                                       "Installation succeeded but binary not found")))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (platform-key)        ;; => "macos-aarch64"
  (which "clojure-lsp") ;; => "/opt/homebrew/bin/clojure-lsp"
  (resolve-binary "clojure-lsp")
  (bin-dir)             ;; => "/Users/chris/.miniforge/bin"

  :leave-this-here)
