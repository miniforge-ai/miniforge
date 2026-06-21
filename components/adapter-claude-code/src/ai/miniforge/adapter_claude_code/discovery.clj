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

(ns ai.miniforge.adapter-claude-code.discovery
  "Discovery of active Claude Code sessions on the local machine.

   Scans ~/.claude/projects/ for active sessions by checking:
   1. Lock files or PID markers
   2. Recent JSONL conversation log activity
   3. Process liveness via PID checks

   Layer 0: Filesystem scanning
   Layer 1: Process liveness detection
   Layer 2: Session extraction"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.control-plane.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Staleness windows (loaded from EDN)

(def ^:private staleness-resource-path
  "Classpath path to the EDN holding session staleness windows."
  "config/adapter_claude_code/staleness.edn")

(defn- load-config
  "Read an EDN config resource, failing fast with a clear ex-info when the
   resource is absent from the classpath, malformed, not a map, or missing
   a required key — rather than a low-signal NPE/reader error at load."
  [path required-keys]
  (let [url (io/resource path)]
    (when (nil? url)
      (throw (ex-info (str "Missing config resource on classpath: " path)
                      {:config/resource path})))
    (let [parsed (try
                   (edn/read-string (slurp url))
                   (catch Exception e
                     (throw (ex-info (str "Malformed EDN config resource: " path)
                                     {:config/resource path} e))))]
      (when-not (map? parsed)
        (throw (ex-info (str "Config resource is not a map: " path)
                        {:config/resource path})))
      (let [missing (remove #(contains? parsed %) required-keys)]
        (when (seq missing)
          (throw (ex-info (str "Config resource " path " missing keys: " (vec missing))
                          {:config/resource path :config/missing-keys (vec missing)})))
        parsed))))

(def staleness-windows
  "Session staleness windows (milliseconds) loaded from the classpath.
   Data lives in resources/config/adapter_claude_code/staleness.edn — a
   missing resource is a packaging error, not a runtime condition."
  (load-config staleness-resource-path
               [:session-activity-window-ms :running-window-ms :idle-window-ms]))

;------------------------------------------------------------------------------ Layer 0
;; Filesystem scanning

(def ^:const claude-base-dir
  "Default Claude Code configuration directory."
  (str (System/getProperty "user.home") "/.claude"))

(def ^:const projects-dir
  "Default projects directory within Claude config."
  (str claude-base-dir "/projects"))

(defn- list-project-dirs
  "List all project directories under ~/.claude/projects/.
   Returns seq of java.io.File directories."
  [base-path]
  (let [dir (io/file base-path)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))
           seq))))

(defn- find-session-files
  "Find JSONL conversation files in a project directory.
   Returns seq of java.io.File files."
  [project-dir]
  (let [sessions-dir (io/file project-dir "sessions")]
    (when (.isDirectory sessions-dir)
      (->> (.listFiles sessions-dir)
           (filter #(str/ends-with? (.getName %) ".jsonl"))
           seq))))

(defn- file-recently-modified?
  "Check if a file was modified within the given threshold (ms)."
  [^java.io.File file threshold-ms]
  (let [last-mod (.lastModified file)
        now (System/currentTimeMillis)]
    (< (- now last-mod) threshold-ms)))

;------------------------------------------------------------------------------ Layer 1
;; Process liveness detection

(defn- pid-alive?
  "Check if a process with the given PID is alive.
   Uses /bin/kill -0 on macOS/Linux."
  [pid]
  (try
    (let [proc (.start (ProcessBuilder. ["kill" "-0" (str pid)]))]
      (.waitFor proc)
      (zero? (.exitValue proc)))
    (catch Exception _ false)))

(defn- find-lock-pid
  "Try to find a PID from lock files in the project directory.
   Returns PID as long, or nil."
  [project-dir]
  (let [lock-file (io/file project-dir ".lock")]
    (when (.exists lock-file)
      (try
        (let [content (str/trim (slurp lock-file))]
          (when (re-matches #"\d+" content)
            (Long/parseLong content)))
        (catch Exception _ nil)))))

;------------------------------------------------------------------------------ Layer 2
;; Session extraction

(def activity-threshold-ms
  "Consider a session active if its log was modified within this window."
  (:session-activity-window-ms staleness-windows))

(defn- project-dir->session-info
  "Extract session info from a project directory.
   Returns agent registration map or nil if no active session."
  [project-dir]
  (let [dir-name (.getName project-dir)
        session-files (find-session-files project-dir)
        latest-session (when (seq session-files)
                         (->> session-files
                              (sort-by #(.lastModified %) >)
                              first))
        recently-active? (and latest-session
                              (file-recently-modified? latest-session
                                                       activity-threshold-ms))
        lock-pid (find-lock-pid project-dir)
        alive? (and lock-pid (pid-alive? lock-pid))]
    (when (or recently-active? alive?)
      {:agent/vendor :claude-code
       :agent/external-id dir-name
       :agent/name (str (messages/t :adapter/claude-code-prefix) dir-name)
       :agent/capabilities #{:code-generation :code-review :test-writing}
       :agent/metadata (cond-> {:project-dir (.getAbsolutePath project-dir)}
                         lock-pid (assoc :pid lock-pid)
                         latest-session (assoc :session-file (.getAbsolutePath latest-session)
                                               :last-activity (java.util.Date. (.lastModified latest-session))))})))

(defn discover-sessions
  "Discover all active Claude Code sessions.

   Arguments:
   - config - Optional map with:
     - :projects-dir - Override default projects directory
     - :activity-threshold-ms - Override activity window

   Returns: Seq of agent registration maps.

   Example:
     (discover-sessions)
     ;=> [{:agent/vendor :claude-code :agent/name \"Claude Code: my-project\" ...}]"
  [& [config]]
  (let [base (get config :projects-dir projects-dir)]
    (->> (list-project-dirs base)
         (keep project-dir->session-info)
         vec)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (discover-sessions)
  (list-project-dirs projects-dir)
  :end)
