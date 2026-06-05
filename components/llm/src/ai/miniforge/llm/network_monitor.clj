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

(ns ai.miniforge.llm.network-monitor
  "Network-drop scheduler for the per-LLM-call lifecycle.

   PR-B of the network-resilience stack — builds on
   `ai.miniforge.llm.network-health/network-healthy?` (PR-A):
   - Spawns a daemon that probes the provider every N seconds while an
     LLM call is in flight.
   - Counts consecutive failures so a transient blip (1 dropped probe)
     does not cost a phase restart.
   - On `failure-threshold` consecutive failures, fires `:on-drop`
     ONCE and exits (the scheduler is single-fire; PR-C handles
     post-drop recovery + retry).

   Mirrors the lifecycle pattern of `progress-monitor/start-keepalive!`:
   `(start-network-monitor! ...)` returns a zero-arity stop-fn the
   caller invokes from `finally` to interrupt the worker and wait for
   it to exit."
  (:require [ai.miniforge.llm.network-health :as nh]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults
;;
;; Operationally tuned constants — kept as named `def`s here (vs an EDN
;; file) per standards rule 007: callers override via the
;; `start-network-monitor!` options map and the values change very
;; rarely.

(def ^:const default-probe-interval-ms
  "Time between probe attempts. 10s — fast enough to detect a drop
   within ~30s (probe interval × failure threshold), slow enough that
   the probe traffic itself does not contribute meaningful load."
  10000)

(def ^:const default-failure-threshold
  "Number of CONSECUTIVE probe failures required to declare a
   confirmed network drop. 3 strikes (= ~30s of dead network at the
   default 10s interval) absorbs transient blips — a single failed
   probe never costs a phase restart."
  3)

(def ^:const monitor-stop-join-ms
  "How long stop! waits for the worker thread to exit after
   interrupting it. The worker only needs to wake from Thread/sleep,
   see the running? flag, and exit."
  200)

;------------------------------------------------------------------------------ Layer 1
;; Scheduler

(defn- run-monitor-loop!
  "Inner worker loop. Polls `network-healthy?` every `probe-interval-ms`;
   tracks consecutive failures. On `failure-threshold` consecutive
   failures, calls `on-drop` with diagnostic data and exits the loop
   (single-fire). On a healthy probe, resets the counter — a brief blip
   does not push the workflow toward a network-drop verdict."
  [{:keys [running? backend-key probe-interval-ms failure-threshold
           probe-fn on-drop]}]
  (loop [consecutive-failures 0]
    (when @running?
      (Thread/sleep ^long probe-interval-ms)
      (when @running?
        (let [healthy? (try (probe-fn backend-key)
                            (catch Exception _ false))
              next-failures (if healthy? 0 (inc consecutive-failures))]
          (cond
            (>= next-failures failure-threshold)
            (when @running?
              (on-drop {:backend-key          backend-key
                        :consecutive-failures next-failures
                        :failure-threshold    failure-threshold
                        :probe-interval-ms    probe-interval-ms}))

            :else
            (recur next-failures)))))))

(defn start-network-monitor!
  "Spawn a daemon thread that probes `backend-key`'s upstream every
   `:probe-interval-ms` ms and fires `:on-drop` after
   `:failure-threshold` consecutive failures (single-fire).

   ## Options

   - `:backend-key`         — the `:llm/backend` keyword (required).
   - `:probe-interval-ms`   — defaults to `default-probe-interval-ms`.
   - `:failure-threshold`   — defaults to `default-failure-threshold`.
                              Must be a positive integer.
   - `:on-drop`             — `(fn [diagnostic-map])` called once when
                              the threshold trips. The map carries
                              `:backend-key`, `:consecutive-failures`,
                              `:failure-threshold`, `:probe-interval-ms`.
   - `:probe-fn`            — override for tests; defaults to
                              `network-health/network-healthy?`. Must
                              accept `(fn [backend-key])` and return
                              truthy/falsey.

   ## Returns

   A zero-arity stop-fn. Calling it interrupts the worker thread and
   waits up to `monitor-stop-join-ms` for it to exit, so callers can
   rely on no further `:on-drop` calls landing once stop! returns."
  [{:keys [backend-key probe-interval-ms failure-threshold on-drop probe-fn]
    :or   {probe-interval-ms default-probe-interval-ms
           failure-threshold default-failure-threshold
           probe-fn          nh/network-healthy?}}]
  (assert (some? backend-key)
          "start-network-monitor! requires a :backend-key")
  (assert (and (integer? probe-interval-ms) (pos? probe-interval-ms))
          "probe-interval-ms must be a positive integer")
  (assert (and (integer? failure-threshold) (pos? failure-threshold))
          "failure-threshold must be a positive integer")
  (assert (ifn? on-drop)
          "on-drop must be a function")
  (let [running? (atom true)
        thread   (Thread.
                   (fn []
                     (try
                       (run-monitor-loop!
                         {:running?          running?
                          :backend-key       backend-key
                          :probe-interval-ms probe-interval-ms
                          :failure-threshold failure-threshold
                          :probe-fn          probe-fn
                          :on-drop           on-drop})
                       (catch InterruptedException _
                         ;; stop! interrupted us mid-sleep; exit cleanly.
                         nil)))
                   "llm-network-monitor")]
    (.setDaemon thread true)
    (.start thread)
    (fn stop! []
      (reset! running? false)
      (.interrupt thread)
      (.join thread monitor-stop-join-ms))))
