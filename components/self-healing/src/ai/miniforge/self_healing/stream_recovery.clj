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

   - `evaluate-stall-recovery` — decides :resume / :failover / :abort.
     NOTE: the :failover and health-gated paths have side effects — they call
     record-backend-call! and trigger-backend-switch! on the backend-health store.
   - `execute-resume!` — side-effecting: relaunch subprocess with resume flag.

   Decision rules
   --------------
   hang-count = 1, backend healthy   → :resume   (transparent retry)
   hang-count = 1, backend unhealthy → :failover (skip wasted retry)
   hang-count >= 2                   → :failover (backend is flaky)
   no candidate                      → :abort    (all failovers exhausted)"
  (:require
   [ai.miniforge.self-healing.backend-health :as backend-health]))

;;------------------------------------------------------------------------------ Layer 0
;; Backend-specific CLI metadata

(def ^:private backend-resume-flags
  "Map of backend keyword → CLI flag used to resume a prior session."
  {:anthropic   "--resume"
   :claude-code "--resume"
   :openai      "--resume"
   :codex       "--resume"
   :ollama      "--continue"
   :google      "--resume"})

(def ^:private backend-binaries
  "Map of backend keyword → actual CLI binary name."
  {:anthropic   "claude"
   :claude-code "claude"
   :openai      "openai"
   :codex       "codex"
   :ollama      "ollama"
   :google      "gemini"})

(defn resume-flag-for
  "Return the backend-specific CLI resume flag.

   Arguments:
     backend - Keyword backend name

   Returns: String CLI flag (defaults to \"--resume\")"
  [backend]
  (get backend-resume-flags (keyword backend) "--resume"))

(defn- binary-for
  "Return the CLI binary name for a backend.

   Arguments:
     backend - Keyword backend name

   Returns: String binary name (defaults to (name backend))"
  [backend]
  (get backend-binaries (keyword backend) (name backend)))

(defn build-resume-command
  "Build the full CLI command vector to resume an agent session.

   Arguments:
     backend    - Keyword backend name (:anthropic, :openai, etc.)
     session-id - String session identifier to resume
     extra-args - Optional seq of additional CLI arguments

   Returns: Vector of strings, e.g. [\"claude\" \"--resume\" \"<sid>\"]"
  ([backend session-id]
   (build-resume-command backend session-id []))
  ([backend session-id extra-args]
   (let [backend-kw (keyword backend)
         flag       (resume-flag-for backend-kw)
         binary     (binary-for backend-kw)]
     (into [binary flag (str session-id)] extra-args))))

;;------------------------------------------------------------------------------ Layer 1
;; Process management (isolated for testability)

(defn- start-process!
  "Launch a subprocess from a command vector.

   Arguments:
     cmd - Seq of strings forming the command

   Returns: java.lang.Process, or throws ex-info on java.io.IOException."
  [cmd]
  (try
    (-> (ProcessBuilder. ^java.util.List (vec cmd))
        (.inheritIO)
        (.start))
    (catch java.io.IOException e
      (throw (ex-info "Failed to start agent subprocess"
                      {:cmd   cmd
                       :cause (ex-message e)}
                      e)))))

;;------------------------------------------------------------------------------ Layer 2
;; Shared failover helper

(defn- perform-failover
  "Record the current backend as unhealthy, select a candidate from
   allowed-failover-backends via backend-health/select-best-backend,
   trigger a switch, and return the decision map.

   Arguments:
     backend-kw                - Keyword current backend
     allowed-failover-backends - Seq of keyword failover candidates
     threshold                 - Double success-rate minimum
     cooldown-ms               - Long cooldown in ms

   Returns: {:action :failover, :new-backend kw} or {:action :abort, ...}"
  [backend-kw allowed-failover-backends threshold cooldown-ms]
  (backend-health/record-backend-call! backend-kw false)
  (let [allowed-set (set (map keyword allowed-failover-backends))
        candidate   (backend-health/select-best-backend
                     backend-kw threshold cooldown-ms allowed-set)]
    (if candidate
      (do
        (backend-health/trigger-backend-switch! backend-kw candidate cooldown-ms)
        {:action      :failover
         :new-backend candidate})
      (do
        (binding [*out* *err*]
          (println (pr-str {:event   :stream-recovery/abort
                            :backend backend-kw
                            :reason  "no healthy backends in allowed-failover-backends"})))
        {:action :abort
         :reason "no healthy backends"}))))

;;------------------------------------------------------------------------------ Layer 3
;; Core decision function

(defn evaluate-stall-recovery
  "Decide whether to resume, failover, or abort after a watchdog kill.

   Side effects on :failover path:
     - record-backend-call! marks current backend unhealthy
     - trigger-backend-switch! records cooldown and updates default-backend

   Decision logic:
     hang-count = 1, backend healthy   → {:action :resume, ...}
       Backend has no known bad health: attempt transparent resume.

     hang-count = 1, backend unhealthy → {:action :failover, ...}
       Skip the wasted retry: backend is already below threshold.
       Same side effects as hang-count >= 2.

     hang-count >= 2                   → {:action :failover, :new-backend kw}
       Backend persistently flaky; records failure and switches.

     no candidate found                → {:action :abort, :reason \"no healthy backends\"}

   Arguments:
     ctx - Map with:
       :phase-id                  - Any; ID of the stalled phase (used in log events)
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
  [{:keys [phase-id backend session-id hang-count config allowed-failover-backends]}]
  (let [count       @hang-count
        backend-kw  (keyword backend)
        cooldown-ms (get config :backend-switch-cooldown-ms 1800000)
        threshold   (get config :backend-health-threshold 0.90)]
    (cond
      ;; First stall — check backend health before committing to a resume attempt
      (= count 1)
      (let [rate (backend-health/get-backend-success-rate backend-kw)]
        (if (and rate (< rate threshold))
          ;; Backend already known-unhealthy; skip the wasted retry
          (do
            (binding [*out* *err*]
              (println (pr-str {:event      :stream-recovery/early-failover
                                :phase-id   phase-id
                                :backend    backend-kw
                                :hang-count count
                                :rate       rate
                                :threshold  threshold})))
            (perform-failover backend-kw allowed-failover-backends threshold cooldown-ms))
          ;; Backend healthy or no data yet — attempt transparent resume
          (do
            (binding [*out* *err*]
              (println (pr-str {:event      :stream-recovery/resume
                                :phase-id   phase-id
                                :backend    backend-kw
                                :hang-count count})))
            {:action     :resume
             :session-id session-id
             :backend    backend-kw})))

      ;; Second or subsequent stall — backend is persistently flaky
      (>= count 2)
      (do
        (binding [*out* *err*]
          (println (pr-str {:event      :stream-recovery/failover
                            :phase-id   phase-id
                            :backend    backend-kw
                            :hang-count count})))
        (perform-failover backend-kw allowed-failover-backends threshold cooldown-ms))

      ;; Degenerate: hang-count <= 0 (should not occur in normal operation)
      :else
      (do
        (binding [*out* *err*]
          (println (pr-str {:event      :stream-recovery/degenerate-hang-count
                            :phase-id   phase-id
                            :backend    backend-kw
                            :hang-count count
                            :anomaly?   true})))
        {:action     :resume
         :session-id session-id
         :backend    backend-kw
         :anomaly?   true}))))

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
      :command    [...]}

   Throws: ex-info with :cmd and :cause keys if the binary is not found
   or not executable."
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
  ;; First stall: resume (backend healthy)
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

  ;; Second stall: failover
  (let [hang (atom 2)]
    (evaluate-stall-recovery
     {:phase-id                  :implement
      :backend                   :anthropic
      :session-id                "sess-abc123"
      :hang-count                hang
      :config                    {}
      :allowed-failover-backends [:openai :codex]}))
  ;; => {:action :failover, :new-backend :openai}

  ;; Build command without launching a process
  (build-resume-command :anthropic "sess-xyz" ["--timeout" "120"]))
  ;; => ["claude" "--resume" "sess-xyz" "--timeout" "120"]
