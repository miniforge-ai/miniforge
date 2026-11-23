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
(ns ai.miniforge.llm.protocols.impl.llm-client.prompts
  "The prompt side of a request: building the prompt from messages, how
   it reaches the agent CLI (argv or stdin), the pre-flight size gauge,
   and the context-overflow check on the input tokens a run used."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} build-messages-prompt [messages]
  (->> messages
       (map (fn [{:keys [role content]}]
              (case role
                "user" content
                "assistant" (str "[Assistant]: " content)
                "system" (str "[System]: " content)
                content)))
       (str/join "\n\n")))

(defn ^{:stratum 0} resolve-prompt-via
  "Read the backend's declared prompt-delivery mode. Defaults to :argv
   so backends that pre-date the :prompt-via knob keep their legacy
   behavior."
  [backend-config]
  (get backend-config :prompt-via :argv))

(defn ^{:stratum 0} exec-opts-for-prompt-via
  "Build the exec-layer opts map for a given prompt-via + prompt.

   :stdin → {:stdin prompt} so the exec layer pipes the prompt to the
            subprocess's stdin.
   :argv  → {} (prompt rides in argv via the args-fn).

   Returning an empty map for :argv lets call sites use `(seq opts)`
   to decide between the 1-arity and 2-arity exec-fn calls — preserves
   back-compat with caller-supplied 1-arity exec-fns documented as a
   public test hook on CLIClient."
  [prompt-via prompt]
  (cond-> {} (= prompt-via :stdin) (assoc :stdin prompt)))

(def ^{:stratum 0} context-overflow-error-type
  "Error :type for a prompt that exceeded the model's context window.
   Terminal: excluded from submission-recovery (the turn has no artifact to
   recover and a retry just re-sends the over-budget prompt). See N12 §4."
  "context_overflow")

(defn- ^{:stratum 0} usage-token-count
  "Return a numeric usage token count, treating missing or malformed values
   as zero so context accounting remains numeric for upstream payloads."
  [usage token-key]
  (let [tokens (get usage token-key)]
    (if (number? tokens) tokens 0)))

(def ^{:stratum 0} ^:private chars-per-token-estimate
  "Rough characters-per-token divisor for a pre-flight input-size estimate.
   ~4 chars/token is the usual English heuristic; this is a coarse gauge of
   headroom against the model's context window, not a billing figure."
  4)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-request-prompt [request]
  (or (:prompt request)
      (build-messages-prompt (:messages request))))

(defn ^{:stratum 1} total-input-tokens
  "prompt + cache-creation + cache-read tokens. Summed because a large
   prompt lands mostly under cache-creation, not :input-tokens. See N12 §2."
  [usage]
  (+ (usage-token-count usage :input-tokens)
     (usage-token-count usage :cache-creation-input-tokens)
     (usage-token-count usage :cache-read-input-tokens)))

(defn ^{:stratum 1} prompt-size-telemetry
  "Pre-flight size gauge over the assembled system + user prompt, computed
   BEFORE dispatch so it survives a context-overflow rejection (where
   `:usage` never arrives). `:estimated-input-tokens` is a coarse chars/4
   estimate, rounded UP so a near-boundary prompt isn't under-reported.
   See N12 §3."
  [system prompt]
  (let [system-chars (count (or system ""))
        user-chars   (count (or prompt ""))
        total-chars  (+ system-chars user-chars)]
    {:system-chars system-chars
     :user-chars user-chars
     :total-chars total-chars
     ;; ceil division (stay conservative for a headroom gauge)
     :estimated-input-tokens (quot (+ total-chars (dec chars-per-token-estimate))
                                   chars-per-token-estimate)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} context-overflow-by-usage?
  "True when total input tokens >= the model's context window — the
   structured, locale/backend-independent overflow signal (N12 §4).
   `context-window` is nil for uncatalogued models → no assertion."
  [usage context-window]
  (boolean (and context-window
                (pos? (total-input-tokens usage))
                (>= (total-input-tokens usage) context-window))))
