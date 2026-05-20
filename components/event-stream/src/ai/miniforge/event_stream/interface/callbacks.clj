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

(ns ai.miniforge.event-stream.interface.callbacks
  "Streaming callback helpers for the event-stream public API."
  (:require
   [clojure.string :as str]
   [ai.miniforge.event-stream.digest :as digest]
   [ai.miniforge.event-stream.interface.events :as events]
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.event-stream.messages :as messages])
  (:import
   [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0
;; Convenience callbacks

(defn- tool-use-line
  "Render a short terminal-visible line for a tool-use streaming event."
  [{:keys [tool-name tool-names]}]
  (let [names (or (seq tool-names)
                  (when tool-name [tool-name]))]
    (when (seq names)
      (messages/t :stream/tool-use-line {:names (str/join ", " names)}))))

(defn- maybe-digest
  "Apply digest-content to `content` only when content is non-nil.
   digest-content handles the 1KB threshold internally (preview is always
   capped; SHA-256 is always computed). We guard only against nil so the
   event constructors receive a clean, fully-formed digest map or nothing."
  [content]
  (when (some? content)
    (digest/digest-content content)))

(defn create-streaming-callback
  "Callback invoked by the LLM client for each parsed stream event.

   Expected parsed-event keys:
     :delta       — text content produced in this block
     :done?       — terminal block
     :tool-use    — boolean, true when this block invoked one or more tools
     :tool-name   — single tool name when available (Claude / codex)
     :tool-names  — vector of tool names (Claude multi-tool blocks)
     :tool-call-id — provider-supplied id when available
     :tool-args-preview — bounded args preview when the parser can safely
                          expose it; will be digested via digest-content
     :tool-result — boolean, true when the LLM streamed a tool-result
                    block (outcome of a prior tool-use)
     :tool-result-call-id — provider id correlating this result to its
                            originating tool-use; may be nil
     :tool-result-content — raw result payload (string or map) when the
                            parser surfaces it; may be nil
     :tool-result-is-error — boolean, true when the result is an error
     :heartbeat   — keepalive, ignored for diagnostic emission

   For tool-use events emits BOTH (in order):
     :agent/tool-call          — legacy event; carries :tool/name /
                                 :tool/names / :tool/call-id /
                                 :tool/args-preview.  Preserved for
                                 backward compatibility with existing
                                 consumers (dashboard, TUI, tests).
     :agent/tool-call-started  — new paired start event; carries
                                 :tool/name / :tool/call-id /
                                 :tool/args-digest (digest-content of
                                 tool-args-preview when present).

   NOTE: The bare :agent/status :tool-calling emission has been removed.
         :agent/tool-call-started replaces it with richer structured data.

   For tool-result events emits:
     :tool/call-completed — closing event for the latency span opened by
                            :agent/tool-call-started.  Carries
                            :tool/call-id / :tool/result-digest /
                            :tool/duration-ms / :tool/success?."
  [stream-atom workflow-id agent-id & [opts]]
  (let [{:keys [print? quiet?]} opts
        ;; Local start-time registry keyed by tool-call-id.
        ;; Populated on tool-use; consumed and cleared on tool-result.
        ;; Accepted trade-off: entries for tool-calls whose result never
        ;; arrives (timeout, cancellation, stream cut) accumulate for the
        ;; lifetime of this callback instance.  Callbacks are short-lived
        ;; per agent turn, so the practical leak bound is O(concurrent
        ;; outstanding tool calls) — negligible in normal operation.
        ;; Monotonic — `nanoTime` is immune to wall-clock adjustments
        ;; (NTP step, manual sysclock changes) that can make a paired
        ;; `currentTimeMillis` delta negative or implausibly large.
        ;; Durations published downstream stay non-negative without a
        ;; clamp/abs guard at the read site.
        tool-start-times (atom {})]
    (fn [{:keys [delta done? tool-use tool-result heartbeat
                 tool-name tool-names tool-call-id tool-args-preview
                 tool-result-call-id tool-result-content tool-result-is-error]}]
      (cond
        tool-use
        (do
          ;; Record monotonic start for subsequent duration computation.
          (when tool-call-id
            (swap! tool-start-times assoc tool-call-id (System/nanoTime)))

          (when-let [line (and print? (not quiet?)
                               (tool-use-line {:tool-name tool-name
                                               :tool-names tool-names}))]
            (print line)
            (flush))

          (when stream-atom
            ;; ── Legacy event — keeps existing consumers working ──────────
            (stream/publish!
              stream-atom
              (events/agent-tool-call stream-atom workflow-id agent-id
                                      {:tool-name tool-name
                                       :tool-names tool-names
                                       :tool-call-id tool-call-id
                                       :tool-args-preview tool-args-preview}))

            ;; ── New paired started event — replaces :agent/status :tool-calling
            (stream/publish!
              stream-atom
              (events/agent-tool-call-started
                stream-atom workflow-id agent-id
                ;; cond-> keeps nil keys out of the map explicitly,
                ;; independent of constructor nil-stripping behaviour.
                ;; :tool/names mirrors the legacy :agent/tool-call field so
                ;; consumers can handle multi-tool provider blocks correctly.
                ;; `some?` mirrors `maybe-digest`'s contract — guard on
                ;; nil only, not truthiness, so an empty-string preview
                ;; (e.g. a tool invoked with no args) still produces a
                ;; consistent digest map rather than silently dropping
                ;; the field.
                (cond-> {:tool/name    tool-name
                         :tool/call-id tool-call-id}
                  (seq tool-names) (assoc :tool/names (vec tool-names))
                  (some? tool-args-preview)
                  (assoc :tool/args-digest
                         (digest/digest-content tool-args-preview)))))))

        ;; Tool-result closes the latency span opened by tool-use.
        ;; Emit :tool/call-completed with duration and digested result.
        tool-result
        (when stream-atom
          (let [correlated-id (or tool-result-call-id tool-call-id)
                start-ns      (when correlated-id
                                (let [t (get @tool-start-times correlated-id)]
                                  (swap! tool-start-times dissoc correlated-id)
                                  t))
                duration-ms   (when start-ns
                                ;; Convert monotonic nanos → ms via TimeUnit
                                ;; rather than a bare 1000000 divisor — the
                                ;; named conversion documents the intent and
                                ;; matches the named-constants rule
                                ;; (.standards/foundations/named-constants.mdc).
                                ;; nanoTime never decreases on the same JVM,
                                ;; so the delta is always non-negative; no
                                ;; clamp needed.
                                (.toMillis TimeUnit/NANOSECONDS
                                           (- (System/nanoTime) start-ns)))
                result-digest (maybe-digest tool-result-content)]
            (stream/publish!
              stream-atom
              (events/tool-call-completed
                stream-atom workflow-id
                ;; cond-> excludes optional keys explicitly when nil,
                ;; decoupled from constructor nil-filtering behaviour.
                (cond-> {:tool/call-id  correlated-id
                         :tool/success? (not (true? tool-result-is-error))}
                  result-digest (assoc :tool/result-digest result-digest)
                  duration-ms   (assoc :tool/duration-ms duration-ms))))))

        heartbeat
        nil

        :else
        (do
          (when (and print? (not quiet?) (seq delta))
            (print delta)
            (flush))
          (when stream-atom
            (stream/publish! stream-atom
                             (events/agent-chunk stream-atom workflow-id agent-id
                                                 (or delta "") done?))))))))
