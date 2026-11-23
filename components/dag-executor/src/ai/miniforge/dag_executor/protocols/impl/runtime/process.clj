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
  "Shared owned-process shutdown and bounded capture for runtime CLI
   and worktree executors."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.process-shutdown :as shutdown]
            [ai.miniforge.subprocess.interface :as subprocess]))

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

(def ^{:stratum 0} interrupted-exit-code
  "Conventional shell SIGINT result, kept distinct from command timeout."
  130)

;; The executors drain stdout/stderr through these names; the capture
;; limit and its behavior live in the subprocess component.
(def ^{:stratum 0} drain-stream subprocess/drain-stream)

(def ^{:stratum 0} drained-text subprocess/drained-text)

(def ^{:stratum 0} drained-bytes subprocess/drained-bytes)

(def ^{:stratum 0} overflowed? subprocess/overflowed?)

(def ^{:stratum 0} cancel-drain! subprocess/cancel-drain!)

(def ^{:stratum 0} output-limit-exit-code subprocess/output-limit-exit-code)

(def ^{:stratum 0} capture-limit subprocess/capture-limit)

(defn ^{:stratum 0} read-stream-future
  "A future of the bytes `stream` holds, read through a bounded drain."
  [stream]
  (let [handle (subprocess/drain-stream stream)]
    (future
      @(:future handle)
      (subprocess/drained-bytes handle))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} settle-drains!
  "For a child that has exited: wait for each `subprocess/drain-stream`
   handle in `drains` to reach end-of-stream until `deadline-ns` (a
   System/nanoTime value), and for at least `graceful-shutdown-ms` so the
   bytes the child wrote are read, then stop any still running
   (`subprocess/cancel-drain!`). True when every drain finished; false
   when one was stopped, so its output may be incomplete. A grandchild
   that keeps the child's stdout or stderr open after the child exits
   therefore cannot hold the caller past its deadline."
  [drains deadline-ns]
  (let [until (max deadline-ns (+ (System/nanoTime) (* graceful-shutdown-ms 1000000)))
        done? (every? (fn [{f :future}]
                        (not= ::open (deref f (max 0 (quot (- until (System/nanoTime)) 1000000)) ::open)))
                      drains)]
    (run! subprocess/cancel-drain! drains)
    done?))

(defn ^{:stratum 1} destroy-process-tree!
  "Signal snapshotted descendants and the owned direct child, leaf first.
   Defer cancellation through forced termination and bounded exit waits,
   then restore the interrupt flag."
  [^Process process]
  (shutdown/stop! process graceful-shutdown-ms))

(comment
  (let [capture (drain-stream (java.io.ByteArrayInputStream. (byte-array 0)) {})]
    @(:future capture)
    (drained-text capture)))
