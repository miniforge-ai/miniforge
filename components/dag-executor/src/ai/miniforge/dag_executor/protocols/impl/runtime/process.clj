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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.process
  "Shared process-tree helpers for executors that shell out.

   The runtime CLI executors (`descriptor` probes, `oci-cli` exec) and the
   worktree executor's `execute-command` all start child processes and
   need to (a) drain stdout/stderr on background threads and (b) kill the
   whole process tree on timeout — the parent typically forks a child
   (a container runtime, or `sh -c` forking the real command), and
   destroying only the parent leaks it. Keeping the helpers in one place
   means a fix to the grace period or kill order lands in every call site."
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.lang ProcessHandle)
           (java.util.concurrent TimeUnit)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} timeout-exit-code
  "Exit code reported when a child is killed for outrunning its deadline.
   Mirrors coreutils `timeout(1)`, which exits 124 on expiry, so callers
   and log readers see one convention whether the bound was enforced by
   the shell utility or by this JVM."
  124)

(def ^{:stratum 0} graceful-shutdown-ms
  "Time we give a process to exit after .destroy before escalating to
   .destroyForcibly. Keep this small — the runtime CLI calls are
   short-lived probes/exec; a stuck child blocks the executor."
  1000)

(defn ^{:stratum 0} read-stream-future
  "Drain `stream` into a future of bytes. The future runs on the agent
   thread pool — fine for short-lived runtime CLI output. Callers should
   `future-cancel` it if they destroy the process before reading
   completes, otherwise the reader thread sits blocked on a dead pipe."
  [stream]
  (future (.readAllBytes stream)))

(defn ^{:stratum 0} drain-stream
  "Copy `stream` into a growable byte buffer on a background thread.
   Returns {:buffer ByteArrayOutputStream :future f}. Unlike
   `read-stream-future`, the bytes copied so far can be read at any time
   via `drained-text`, so a caller that kills the process at its deadline
   can still surface the partial stdout/stderr the child managed to write.
   `ByteArrayOutputStream` synchronizes its methods, so sampling from
   another thread while the copy is still running is safe."
  [^InputStream stream]
  (let [buffer (ByteArrayOutputStream.)]
    {:buffer buffer
     :future (future (.transferTo stream buffer))}))

(defn ^{:stratum 0} drained-text
  "UTF-8 text copied so far by a `drain-stream` handle."
  [{:keys [^ByteArrayOutputStream buffer]}]
  (.toString buffer "UTF-8"))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} destroy-process-tree!
  "Kill `process` and every descendant. Tries SIGTERM first (children
   in reverse-discovery order, then the parent), waits up to
   `graceful-shutdown-ms` for the parent to exit, and escalates to
   SIGKILL on anything still alive.

   Why reverse-order: descendants are listed parent-to-leaf; killing
   leaves first reduces the chance an intermediate parent immediately
   forks a replacement when its child dies."
  [^Process process]
  (let [handle      (.toHandle process)
        descendants (reverse (iterator-seq (.iterator (.descendants handle))))]
    (doseq [^ProcessHandle child descendants]
      (.destroy child))
    (.destroy process)
    (.waitFor process graceful-shutdown-ms TimeUnit/MILLISECONDS)
    (doseq [^ProcessHandle child descendants]
      (when (.isAlive child)
        (.destroyForcibly child)))
    (when (.isAlive process)
      (.destroyForcibly process))))
