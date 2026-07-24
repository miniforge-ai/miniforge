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

(ns ai.miniforge.cli.workflow-runner.control
  "Governed control-path wiring shared by every CLI runner path
   (Phase D D-3/D-4).

   A runner becomes controllable by doing two things: registering its
   in-process control handles under its workflow id, and making sure
   this process is running exactly one operator-event consumer. Every
   runner path in the CLI — spec runner, chain runner, plan executor,
   resume — needs the identical pair, so it lives here once rather than
   three-and-a-half times.

   The meta-loop context is here too because the consumer publishes its
   lifecycle events onto that context's operator-level stream, and the
   degradation manager it owns is what safe-mode interventions act on.
   Both are process-scoped singletons: a second consumer would race the
   shared on-disk cursor and could publish a workflow's audit trail
   through the wrong stream."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [ai.miniforge.supervisory-state.interface :as supervisory]))

;------------------------------------------------------------------------------ Layer 0
;; Process-scoped singletons

(defonce ^:private meta-loop-ctx
  ;; Lazily initialized when the first governed workflow starts.
  ;; Uses a dedicated operator-level event stream (no workflow-id → operator.edn).
  (atom nil))

(defonce ^:private operator-consumer-handle
  ;; Exactly one operator-directory consumer per process. Starting one per
  ;; workflow races the shared on-disk cursor and can publish a target
  ;; workflow's audit trail through the wrong workflow stream.
  (atom nil))

(defn- create-meta-loop-ctx!
  []
  (let [operator-stream (es/create-event-stream)
        _supervisor (supervisory/attach! operator-stream)
        ;; N15-6: route routing-causality through the witness surface.
        ;; The correlator subscribes to the same stream as
        ;; supervisory-state and emits `:supervisory/automation-edge-upserted`
        ;; events; consumers (Rust core, native app) dedup on `:edge/id`.
        _correlator (correlator/attach! operator-stream)]
    (agent/create-meta-loop-context operator-stream)))

(defn meta-loop-context!
  "The process-wide meta-loop context, created on first use."
  []
  ;; Double-checked locking. The inner `or` is NOT the outer one repeated:
  ;; the outer read is the fast path (no lock once initialized); the inner
  ;; read re-checks after acquiring the lock, because a racing thread may
  ;; have created the context in the window between the outer read
  ;; returning nil and this thread taking the lock. Without it, two first
  ;; callers would each `reset!` a distinct context (and a second
  ;; `supervisory/attach!` + `correlator/attach!` onto the stream).
  (or @meta-loop-ctx
      (locking meta-loop-ctx
        (or @meta-loop-ctx
            (reset! meta-loop-ctx (create-meta-loop-ctx!))))))

;------------------------------------------------------------------------------ Layer 1
;; Consumer lifecycle

(defn- ensure-operator-consumer!
  [ctx]
  ;; Double-checked locking (see meta-loop-context!). The inner re-check
  ;; matters more here: without it a race would `start-operator-consumer!`
  ;; twice, leaving a second poller thread orphaned — its handle
  ;; overwritten and never stopped.
  (or @operator-consumer-handle
      (locking operator-consumer-handle
        (or @operator-consumer-handle
            (reset! operator-consumer-handle
                    (operator/start-operator-consumer!
                     {:stream (:event-stream ctx)
                      :apply! operator/apply-intervention!
                      :accept? operator/live-intervention-target?
                      :stream-for operator/live-intervention-stream}))))))

;------------------------------------------------------------------------------ Layer 2
;; Runner registration

(defn register-workflow-control!
  "Make `workflow-id` controllable from the governed operator channel:
   publish this runner's control handles and guarantee the process
   consumer is polling. Call inside the runner's `try`, after every
   binding that can throw, and pair with [[release-workflow-control!]]
   in the matching `finally`."
  [workflow-id control-state event-stream]
  (let [ctx (meta-loop-context!)
        handles {:control-state control-state
                 :event-stream event-stream}]
    (operator/register-degradation-manager! (:degradation-manager ctx))
    (operator/register-live-runner! workflow-id handles)
    (try
      (ensure-operator-consumer! ctx)
      (catch Throwable e
        (operator/deregister-live-runner! workflow-id)
        (throw e)))))

(defn release-workflow-control!
  "Drop `workflow-id` from the live-runner registry. Interventions
   aimed at it stop being applicable the moment the runner is gone —
   which is the honest answer, not a silent no-op. Idempotent."
  [workflow-id]
  (operator/deregister-live-runner! workflow-id))
