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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.drain
  "Deadline handling for `execute-command`: settling the stdout/stderr
   drains and collecting a killed process tree's partial output."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.process :as runtime-process]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} partial-output-drain-ms
  "How long a deadline-bounded `execute-command` waits for the stdout/stderr
   drain threads to reach end-of-stream — after killing a timed-out process
   tree, and after the child exits on its own — before it samples whatever
   bytes they copied. One second covers the pipe buffers emptying once the
   writers are dead; if an orphan grandchild still holds a pipe open the
   sample is taken anyway, so the deadline holds and the caller gets
   partial output rather than a second hang."
  1000)

(defn ^{:stratum 0} timed-out-result
  "Result for a child killed at its deadline: exit `timeout-exit-code`, the
   partial stdout/stderr captured before the kill, and `:timed-out? true`
   at the top level — the shape `verify/run-tests-in-capsule!` routes as a
   non-actionable timeout instead of a test failure."
  [start-time stdout-drain stderr-drain]
  (assoc (result/ok {:exit-code   runtime-process/timeout-exit-code
                     :stdout      (runtime-process/drained-text stdout-drain)
                     :stderr      (runtime-process/drained-text stderr-drain)
                     :duration-ms (- (System/currentTimeMillis) start-time)})
         :timed-out? true))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} settle-drains!
  "Wait up to `partial-output-drain-ms` for both drains to reach
   end-of-stream, then close and cancel whatever is still running. The
   cancel is a no-op on a finished drain; on an unfinished one it frees
   the reader thread instead of leaking it on a pipe an orphan holds open."
  [stdout-drain stderr-drain]
  (deref (:future stdout-drain) partial-output-drain-ms nil)
  (deref (:future stderr-drain) partial-output-drain-ms nil)
  (runtime-process/cancel-drain! stdout-drain)
  (runtime-process/cancel-drain! stderr-drain))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} kill-and-collect!
  "Destroy `process` and its descendants, then settle the drains so no
   reader sits on a pipe an orphan still holds open."
  [^Process process stdout-drain stderr-drain]
  (runtime-process/destroy-process-tree! process)
  (settle-drains! stdout-drain stderr-drain))
