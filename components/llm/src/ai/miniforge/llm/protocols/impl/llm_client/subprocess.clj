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
(ns ai.miniforge.llm.protocols.impl.llm-client.subprocess
  "Subprocess plumbing for the agent CLI: its environment and stdin, the
   stdout and stderr readers, the process-tree kill, and starting a capture
   process."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io ByteArrayInputStream]
   [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} stream-reader-join-timeout-ms
  "How long process-stream-lines waits for its daemon reader thread to
   exit after the stream reader is closed."
  100)

(defn ^{:stratum 0} daemon-thread!
  [thread-name f]
  (let [thread (Thread. ^Runnable f thread-name)]
    (.setDaemon thread true)
    (.start thread)
    thread))

(defn ^{:stratum 0} host-env
  "miniforge's own environment, as a map. Tests redefine it."
  []
  (into {} (System/getenv)))

(defn ^{:stratum 0} allowed-env
  "The entries of `env` whose names `policy` (the :agent-env client
   default) allows: listed in :allow or starting with one of
   :allow-prefixes, and starting with none of :deny-prefixes."
  [{:keys [allow allow-prefixes deny-prefixes]} env]
  (let [listed?  (set allow)
        prefixed? (fn [k prefixes] (some #(str/starts-with? k %) prefixes))]
    (into {}
          (filter (fn [[k _]]
                    (and (or (listed? k) (prefixed? k allow-prefixes))
                         (not (prefixed? k deny-prefixes)))))
          env)))

(defn- ^{:stratum 0} ->stdin-stream
  "Build the InputStream passed as the subprocess's stdin. A non-blank
   :stdin string becomes a ByteArrayInputStream over its UTF-8 bytes;
   absent or blank stdin maps to a zero-length stream so the
   subprocess sees EOF immediately (legacy behavior for argv backends)."
  [stdin-str]
  (if (and (string? stdin-str) (not (.isEmpty ^String stdin-str)))
    (ByteArrayInputStream. (.getBytes ^String stdin-str "UTF-8"))
    (ByteArrayInputStream. (byte-array 0))))

(defn ^{:stratum 0} apply-process-env!
  [^ProcessBuilder builder env]
  (when env
    (let [target-env (.environment builder)]
      (.clear target-env)
      (doseq [[k v] env]
        (.put target-env k v)))))

(defn ^{:stratum 0} wait-for-stream-process
  [^Process process timeout-ms]
  (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
    {:exit (.exitValue process)}
    {:exit -1 :err "Process timed out"}))

(defn ^{:stratum 0} destroy-process-tree!
  "Forcibly kill `process` and every process it spawned.

   `.destroyForcibly` alone kills only the agent CLI: its MCP servers and
   Bash tool children are re-parented and keep running, still writing to
   the worktree. The descendants are listed while the parent is alive
   (once it dies they are re-parented and drop out of the list), killed
   leaf-first, then the parent: the same tree kill verify and the
   dag-executor runtime use. Does not wait for exit."
  [^Process process]
  (let [descendants (try+
                      (vec (.toArray (.descendants process)))
                      ;; A Process with no native handle (a test proxy)
                      ;; has no descendants to list.
                      (catch UnsupportedOperationException _
                        []))]
    (doseq [^java.lang.ProcessHandle child (rseq descendants)]
      (.destroyForcibly child))
    (.destroyForcibly process)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} agent-env
  "The environment an agent CLI process gets: `host-env` filtered by the
   :agent-env client default. Credentials miniforge itself holds
   (GH_TOKEN, AWS_*, SSH_AUTH_SOCK) stay out, and so does CLAUDECODE,
   which would make a nested Claude CLI refuse to start."
  []
  (allowed-env (settings/client-default [:agent-env]) (host-env)))

(defn ^{:stratum 1} write-process-stdin!
  [^Process process stdin-str]
  (daemon-thread!
   "llm-stream-stdin-writer"
   (fn []
     (try
       (with-open [out (.getOutputStream process)
                   in (->stdin-stream stdin-str)]
         (io/copy in out))
       (catch Exception _
         nil)))))

(defn ^{:stratum 1} read-process-stream!
  [thread-name input-stream output]
  (daemon-thread!
   thread-name
   (fn []
     (try
       (reset! output (slurp input-stream))
       (catch Exception e
         (reset! output (or (ex-message e) (str e))))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-process-stderr!
  [^Process process stderr]
  (read-process-stream! "llm-stream-stderr-reader"
                        (.getErrorStream process)
                        stderr))

(defn ^{:stratum 2} start-capture-process!
  [cmd {:keys [workdir stdin]}]
  (let [builder (ProcessBuilder. ^java.util.List cmd)]
    (apply-process-env! builder (agent-env))
    (when workdir
      (.directory builder (io/file workdir)))
    (let [process (.start builder)
          stdout (atom "")
          stderr (atom "")
          stdin-thread (write-process-stdin! process stdin)
          stdout-thread (read-process-stream! "llm-stdout-reader"
                                             (.getInputStream process)
                                             stdout)
          stderr-thread (read-process-stream! "llm-stderr-reader"
                                             (.getErrorStream process)
                                             stderr)]
      {:process process
       :stdout stdout
       :stderr stderr
       :stdin-thread stdin-thread
       :stdout-thread stdout-thread
       :stderr-thread stderr-thread})))
