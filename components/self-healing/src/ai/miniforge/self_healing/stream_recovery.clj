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

(ns ai.miniforge.self-healing.stream-recovery
  "Resume-on-kill logic: decide resume vs failover vs abort after a watchdog kill.

   After a stall watchdog terminates a hung agent subprocess, the caller must
   decide what to do next.  This namespace provides two functions:

   - `evaluate-stall-recovery` — pure decision: resume / failover / abort
   - `execute-resume!`         — side-effecting: relaunch subprocess with resume flag

   Decision rules
   --------------
   hang-count = 1  → :resume   (same backend, same session — first stall may be transient)
   hang-count >= 2 → :failover (backend is flaky; record failure, select healthiest alternative)
   no candidate    → :abort    (all failover backends are in cooldown or unhealthy)"
  (:require
   [ai.miniforge.self-healing.backend-health :as backend-health]))

;;------------------------------------------------------------------------------ Layer 0
;; Backend-specific resume CLI flags

(def ^:private backend-resume-flags
  "Map of backend keyword → CLI flag used to resume a prior session."
  {:anthropic   "--resume"
   :claude-code "--resume"
   :openai      "--resume"
   :codex       "--resume"
   :ollama      "--continue"
   :google      "--resume"})

(defn resume-flag-for
  "Return the backend-specific CLI resume flag.

   Arguments:
     backend - Keyword backend name

   Returns: String CLI flag (defaults to \"--resume\")"
  [backend]
  (get backend-resume-flags (keyword backend) "--resume"))

(defn build-resume-command
  "Build the full CLI command vector to resume an agent session.

   Arguments:
     backend    - Keyword backend name (:anthropic, :openai, etc.)
     session-id - String session identifier to resume
     extra-args - Optional seq of additional CLI arguments

   Returns: Vector of strings, e.g. [\"anthropic\" \"--resume\" \"<sid>\"]"
  ([backend session-id]
   (build-resume-command backend session-id []))
  ([backend session-id extra-args]
   (let [backend-kw (keyword backend)
         flag       (resume-flag-for backend-kw)
         binary     (name backend-kw)]
     (into [binary flag (str session-id)] extra-args))))

;;------------------------------------------------------------------------------ Layer 1
;; Process management (isolated for testability)

(defn- start-process!
  "Launch a subprocess from a command vector.

   Arguments:
     cmd - Seq of strings forming the command

   Returns: java.lang.Process"
  [cmd]
  (-> (ProcessBuilder. ^java.util.List (vec cmd))
      (doto (.redirectErrorStream true)
            (.inheritIO))
      (.start)))

;;------------------------------------------------------------------------------ Layer 2
;; Failover candidate selection

(defn- pick-failover-candidate
  "Select the best backend from allowed-failover-backends, respecting health data.

   Iterates the global fallback-order (from persistent health) and returns the
   first backend that:
     1. Is not the current backend
     2. Is present in allowed-failover-backends
     3. Is not in cooldown
     4. Has no data (treated as healthy) or success-rate >= threshold

   Arguments:
     current-backend          - Keyword current backend to exclude
     allowed-failover-backends - Seq of keyword backends eligible for failover
     threshold                - Double success-rate minimum (e.g. 0.90)
     cooldown-ms              - Long cooldown period in ms

   Returns: Keyword backend or nil if none qualifies"
  [current-backend allowed-failover-backends threshold cooldown-ms]
  (let [backend-kw  (keyword current-backend)
        allowed-set (set (map keyword allowed-failover-backends))
        health      (backend-health/load-health)
        order       (:fallback-order health)]
    (first
     (filter
      (fn [b]
        (and (not= b backend-kw)
             (contains? allowed-set b)
             (not (backend-health/in-cooldown? b cooldown-ms))
             (if-let [rate (backend-health/get-backend-success-rate b)]
               (>= rate threshold)
               true)))  ; no data → eligible
      order))))

;;------------------------------------------------------------------------------ Layer 3
;; Core decision function

(defn evaluate-stall-recovery
  "Decide whether to resume, failover, or abort after a watchdog kill.

   Decision logic:
     hang-count = 1  → {:action :resume, ...}
       Same backend, same session.  Single stall may be transient.

     hang-count >= 2 → {:action :failover, :new-backend kw}
       Backend is persistently flaky.
       Side effects: records backend failure via record-backend-call!,
                     calls trigger-backend-switch! on the chosen candidate.

     no candidate    → {:action :abort, :reason \"no healthy backends\"}
       All allowed failover backends are in cooldown or unhealthy.

   Arguments:
     ctx - Map with:
       :phase-id                  - Any; ID of the stalled phase (for logging)
       :backend                   - Keyword or string; current backend
       :session-id                - String session ID to resume
       :hang-count                - clojure.lang.IAtom<Long>; deref'd to read count
       :config                    - Map; self-healing config section, may contain
                                    :backend-switch-cooldown-ms and
                                    :backend-health-threshold
       :allowed-failover-backends - Seq of keywords; eligible failover targets

   Returns: One of:
     {:action :resume,   :session-id sid,  :backend current-backend-kw}
     {:action :failover, :new-backend kw}
     {:action :abort,    :reason \"no healthy backends\"}"
  [{:keys [backend session-id hang-count config allowed-failover-backends]
    :as _ctx}]
  (let [count       @hang-count
        backend-kw  (keyword backend)
        cooldown-ms (get config :backend-switch-cooldown-ms 1800000)
        threshold   (get config :backend-health-threshold 0.90)]
    (cond
      ;; First stall — attempt transparent resume
      (= count 1)
      {:action     :resume
       :session-id session-id
       :backend    backend-kw}

      ;; Second or subsequent stall — failover
      (>= count 2)
      (do
        ;; Record backend as unhealthy
        (backend-health/record-backend-call! backend-kw false)
        (let [candidate (pick-failover-candidate
                         backend-kw
                         allowed-failover-backends
                         threshold
                         cooldown-ms)]
          (if candidate
            (do
              (backend-health/trigger-backend-switch! backend-kw candidate cooldown-ms)
              {:action      :failover
               :new-backend candidate})
            {:action :abort
             :reason "no healthy backends"})))

      ;; Degenerate: hang-count <= 0 (should not occur) — treat as resume
      :else
      {:action     :resume
       :session-id session-id
       :backend    backend-kw})))

;;------------------------------------------------------------------------------ Layer 4
;; Public subprocess restart

(defn execute-resume!
  "Restart an agent subprocess using the backend-specific resume flag.

   Constructs a command of the form:
     <backend-binary> <resume-flag> <session-id> [extra-args...]

   and launches it with inherited stdio.

   Arguments:
     backend    - Keyword backend name (:anthropic, :openai, :ollama, etc.)
     session-id - String session ID to resume
     extra-args - Optional seq of additional CLI arguments

   Returns: Map with:
     {:process    java.lang.Process
      :backend    backend-kw
      :session-id session-id-str
      :command    [...]}"
  ([backend session-id]
   (execute-resume! backend session-id []))
  ([backend session-id extra-args]
   (let [cmd (build-resume-command backend session-id extra-args)]
     {:process    (start-process! cmd)
      :backend    (keyword backend)
      :session-id (str session-id)
      :command    cmd})))

;;------------------------------------------------------------------------------ Rich comment

(comment
  ;; Simulating a first-stall resume decision
  (let [hang (atom 1)]
    (evaluate-stall-recovery
     {:phase-id                  :implement
      :backend                   :anthropic
      :session-id                "sess-abc123"
      :hang-count                hang
      :config                    {:backend-switch-cooldown-ms 1800000
                                  :backend-health-threshold   0.90}
      :allowed-failover-backends [:openai :codex]}))
  ;; => {:action :resume, :session-id "sess-abc123", :backend :anthropic}

  ;; Simulating a second-stall failover decision
  (let [hang (atom 2)]
    (evaluate-stall-recovery
     {:phase-id                  :implement
      :backend                   :anthropic
      :session-id                "sess-abc123"
      :hang-count                hang
      :config                    {}
      :allowed-failover-backends [:openai :codex]}))
  ;; => {:action :failover, :new-backend :openai}   (or :codex if openai is in cooldown)

  ;; Build resume command without launching a process
  (build-resume-command :anthropic "sess-xyz" ["--timeout" "120"]))
  ;; => ["anthropic" "--resume" "sess-xyz" "--timeout" "120"]
