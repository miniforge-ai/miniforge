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

(ns ai.miniforge.policy-pack.capability
  "Registry of named mechanical-check capabilities.

   A capability names a mechanical check (lint, format, syntax, no-secrets)
   and binds it to a check-fn `(fn [artifact context] -> violation-or-nil)`.
   A pack rule declares `:detection {:type :capability :capability :lint}`
   and the detection dispatcher (see `detection/detect-capability`) runs the
   registered capability's `:check`.

   Dependency direction (read before adding seeds):
   policy-pack is depended ON by `gate` / `compliance-scanner`, so policy-pack
   MUST NOT depend on `loop`/`gate` — that would be a cycle. Therefore the
   mechanical tool-gate capabilities (:lint, :format, :syntax, :no-secrets)
   are NOT seeded here. They are INJECTED at runtime by the `gate` layer (see
   `ai.miniforge.gate.capabilities/register-mechanical-capabilities!`), which
   already depends on both policy-pack and the tool gates. This namespace owns
   only the registry and the registration API; it does no requiring-resolve.

   The registry therefore starts EMPTY and is populated by injection.

   All public fns return data: malformed input yields an `:invalid-input`
   anomaly map rather than throwing (N4 §3.1 — anomalies are data at the
   component interface)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0
;; Registry state

(defonce ^{:doc "Registry of capability-keyword -> {:meta map :check fn}.
   Starts empty; mechanical capabilities are injected by the gate layer."}
  registry
  (atom {}))

;------------------------------------------------------------------------------ Layer 1
;; Validation

(defn- valid-registration?
  "True when `entry` is a well-formed capability registration:
   a map with a `:meta` map and an `:check` that is an actual function
   (a fn). Keywords/symbols are IFn but are not valid checks, so `fn?`
   (not `ifn?`) is required."
  [entry]
  (and (map? entry)
       (map? (:meta entry))
       (fn? (:check entry))))

;------------------------------------------------------------------------------ Layer 2
;; Public API — all return data, never throw

(defn register-capability!
  "Register (or replace) capability `k` with `entry`. Returns the stored
   entry on success, or an `:invalid-input` anomaly map on bad input.

   - `k`     must be a keyword.
   - `entry` must be `{:meta map :check fn}` where `:check` is
     `(fn [artifact context] -> violation-or-nil)`."
  [k entry]
  (cond
    (not (keyword? k))
    (anomaly/anomaly :invalid-input
                     "Capability key must be a keyword"
                     {:key k})

    (not (valid-registration? entry))
    (anomaly/anomaly :invalid-input
                     "Capability entry must be {:meta map :check fn}"
                     {:key k :entry entry})

    :else
    (do (swap! registry assoc k entry)
        entry)))

(defn get-capability
  "Return the registered capability entry for `k`, or nil when unregistered."
  [k]
  (get @registry k))

(defn list-capabilities
  "Return the set of registered capability keywords."
  []
  (set (keys @registry)))

(defn capability-available?
  "True when capability `k` is registered."
  [k]
  (contains? @registry k))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; The registry is seeded by the gate layer, not here:
  ;;   (require '[ai.miniforge.gate.capabilities :as cap])
  ;;   (cap/register-mechanical-capabilities!)
  (list-capabilities)
  (capability-available? :lint)

  ;; Bad input returns an anomaly, never throws.
  (register-capability! "not-a-kw" {:meta {} :check (fn [_ _])})
  (register-capability! :x {:meta {} :check :not-a-fn})

  :leave-this-here)
