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
(ns ai.miniforge.cli.workflow-runner.process
  "Subprocess primitives for backend CLI probes: environment scrubbing,
   stream capture, and bounded-timeout command execution. Split out of
   `ai.miniforge.cli.workflow-runner` (rule 210: the parent namespace
   measured 10 real layers, max 3; each concern moves to its own
   layer-coherent file)."
  (:require
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} cli-process-env
  []
  (when (System/getenv "CLAUDECODE")
    (into {} (remove (fn [[k _]] (= k "CLAUDECODE"))) (System/getenv))))

(defn- ^{:stratum 0} stream-reader
  [stream]
  (future
    (with-open [stream stream]
      (slurp stream))))

(defn- ^{:stratum 0} destroy-cli-process!
  [^Process process]
  (try
    (.destroyForcibly process)
    (.waitFor process 1000 java.util.concurrent.TimeUnit/MILLISECONDS)
    (catch Exception _ nil)))

(defn- ^{:stratum 0} await-stream
  [stream-future]
  (try
    @stream-future
    (catch Exception _ "")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} start-cli-process
  [cmd workdir]
  (let [builder (ProcessBuilder. ^java.util.List cmd)]
    (when-let [process-env (cli-process-env)]
      (let [env (.environment builder)]
        (.clear env)
        (doseq [[k v] process-env]
          (.put env k v))))
    (when workdir
      (.directory builder (java.io.File. workdir)))
    (.start builder)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-cli-command
  [cmd timeout-ms & {:keys [workdir]}]
  (let [^Process process (start-cli-process cmd workdir)
        _ (.close (.getOutputStream process))
        out-future (stream-reader (.getInputStream process))
        err-future (stream-reader (.getErrorStream process))
        completed? (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
    (if-not completed?
      (do
        (destroy-cli-process! process)
        (future-cancel out-future)
        (future-cancel err-future)
        {:out ""
         :err (messages/t :workflow-runner/cli-process-timeout
                          {:timeout-ms timeout-ms})
         :exit -1
         :timeout-ms timeout-ms})
      {:out (await-stream out-future)
       :err (await-stream err-future)
       :exit (.exitValue process)})))
