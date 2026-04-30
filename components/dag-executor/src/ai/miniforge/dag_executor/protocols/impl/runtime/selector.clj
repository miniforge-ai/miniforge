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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.selector
  "Resolve a runtime descriptor from config + host state.

   Implements N11-delta §3 selection algorithm:
     1. Explicit `:runtime-kind` in config wins. Probe the runtime; if the
        probe fails, return an error. NEVER silently fall back when the
        user named a specific runtime — surfacing that the named runtime
        is missing is the whole point of allowing explicit configuration.
     2. No explicit kind: probe `probe-order` in sequence. First whose
        runtime-info probe returns `:available? true` is selected. The
        full probed list is returned alongside so the doctor can show the
        user every runtime it tried.
     3. Nothing available: return an error with the probed list. Callers
        can fall through to a non-OCI executor (e.g. worktree) if their
        contract allows."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def probe-order
  "Auto-probe order. Podman first as the OSS-preferred default per
   N11-delta; Docker second for compatibility; nerdctl is :supported?
   false today and is filtered out at probe time."
  [:podman :docker :nerdctl])

;------------------------------------------------------------------------------ Layer 1
;; Probe — single-kind helper used by both explicit and auto paths

(defn- probe-kind!
  "Build a descriptor for `kind` and run the runtime-info probe. Returns
   {:kind :descriptor :probe-result} so callers can surface every part
   (the descriptor for the chosen runtime, the probe-result for the
   doctor's per-kind report)."
  [kind]
  (let [d            (descriptor/make-descriptor {:runtime-kind kind})
        probe-result (descriptor/runtime-info d)]
    {:kind         kind
     :descriptor   d
     :probe-result probe-result}))

(defn- probe-summary
  "Compact map describing a single probe outcome — the shape the doctor
   command renders. Strips the descriptor itself so logs/output stay
   small; callers that need the descriptor get it from the chosen entry."
  [{:keys [kind probe-result]}]
  {:kind            kind
   :available?      (boolean (:available? probe-result))
   :runtime-version (:runtime-version probe-result)
   :reason          (:reason probe-result)})

;------------------------------------------------------------------------------ Layer 2
;; Selection

(defn- select-explicit
  "Resolve an explicit :runtime-kind. Fails loudly when the named runtime
   is unsupported or when its probe fails — never falls back."
  [kind]
  (if-not (registry/supported? kind)
    (result/err :runtime/explicit-unsupported
                (str "Runtime kind " kind " is not supported.")
                {:kind      kind
                 :supported (registry/supported-kinds)})
    (let [{:keys [descriptor probe-result]} (probe-kind! kind)]
      (if (:available? probe-result)
        (result/ok {:descriptor      descriptor
                    :kind            kind
                    :selection       :explicit
                    :runtime-version (:runtime-version probe-result)})
        (result/err :runtime/explicit-unavailable
                    (str "Runtime " kind " is configured but unavailable.")
                    {:kind   kind
                     :reason (:reason probe-result)})))))

(defn- supported-probe-order
  "probe-order filtered to runtimes the registry currently supports.
   Phase 3 ships [:podman :docker]; nerdctl is filtered out until Phase 4+."
  []
  (filterv registry/supported? probe-order))

(defn- run-auto-probe
  "Walk the supported probe-order, building a per-kind summary list.
   Returns {:probed [...] :winner {...|nil}} where :winner is the first
   summary with :available? true plus the descriptor."
  []
  (let [probes  (mapv probe-kind! (supported-probe-order))
        probed  (mapv probe-summary probes)
        winner  (some (fn [{:keys [probe-result] :as p}]
                        (when (:available? probe-result) p))
                      probes)]
    {:probed probed :winner winner}))

(defn- select-auto
  "Auto-probe selection per N11-delta §3 step 2."
  []
  (let [{:keys [probed winner]} (run-auto-probe)]
    (if winner
      (result/ok {:descriptor      (:descriptor winner)
                  :kind            (:kind winner)
                  :selection       :auto-probe
                  :runtime-version (:runtime-version (:probe-result winner))
                  :probed          probed})
      (result/err :runtime/none-available
                  "No OCI-compatible container runtime is available."
                  {:probed probed}))))

(defn select-runtime
  "Resolve a runtime descriptor from `config`.

   Returns a result map. On success, `:data` carries:
     {:descriptor       <runtime descriptor>
      :kind             <runtime kind keyword>
      :selection        :explicit | :auto-probe
      :runtime-version  <string>
      :probed           [<per-kind summary>]   ; auto-probe only}

   Errors:
     :runtime/explicit-unsupported   — explicit kind is not :supported?
     :runtime/explicit-unavailable   — explicit kind probe failed
     :runtime/none-available         — auto-probe found nothing usable

   Callers SHOULD render the error data via i18n; this function returns
   data, not strings, so the doctor and the CLI can localize."
  [config]
  (if-let [kind (:runtime-kind config)]
    (select-explicit kind)
    (select-auto)))
