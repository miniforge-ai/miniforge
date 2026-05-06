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

(ns ai.miniforge.progress-detector.config
  "Detector config merge semantics — overlay resolution.

   Config layers are resolved top-down. Each layer may declare a
   :config/directive to control how it interacts with layers above it:

     :inherit  - take parent value unchanged; this layer adds nothing
     :disable  - suppress the detector; all other keys in this layer ignored
     :enable   - force-enable even when parent has :disable
     :tune     - deep-merge this layer's :config/params onto parent defaults

   Resolution is transitive: layers are applied left-to-right (outermost →
   innermost). The first :disable wins unless a later layer has :enable.

   Public API:
     merge-config      - resolve a sequence of overlay layers into final config
     apply-directive   - apply one overlay layer onto an accumulated config
     enabled?          - return true if resolved config enables the detector
     effective-params  - extract merged :config/params map from resolved config"
  )

;------------------------------------------------------------------------------ Layer 0
;; Directive resolution

(def ^:private directive-precedence
  "Ordered list from lowest to highest priority within a single overlay step.
   Used to sanity-check overlay intent; resolution itself is positional."
  [:inherit :tune :enable :disable])

(defn- resolve-directive
  "Normalize the directive from a config overlay layer.
   Returns :inherit if no directive declared."
  [layer]
  (get layer :config/directive :inherit))

;------------------------------------------------------------------------------ Layer 0
;; Deep-merge helper

(defn- deep-merge
  "Recursively merge maps. Non-map values in `b` overwrite `a`."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

;------------------------------------------------------------------------------ Layer 1
;; Single-layer overlay application

(defn apply-directive
  "Apply one overlay `layer` onto `accumulated` config.

   Directive semantics:
     :inherit - return accumulated unchanged
     :disable - mark accumulated as disabled (set :detector/enabled? false)
     :enable  - re-enable accumulated (set :detector/enabled? true)
     :tune    - deep-merge layer's :config/params into accumulated :config/params

   The :detector/enabled? flag propagates through subsequent layers.

   Arguments:
     accumulated - current resolved config map
     layer       - overlay map with optional :config/directive and :config/params

   Returns: New config map with overlay applied."
  [accumulated layer]
  (let [directive (resolve-directive layer)
        params    (get layer :config/params {})]
    (case directive
      :inherit
      accumulated

      :disable
      (assoc accumulated :detector/enabled? false)

      :enable
      (assoc accumulated :detector/enabled? true)

      :tune
      (-> accumulated
          (update :config/params deep-merge params)
          ;; :tune does not affect enabled? unless explicitly set
          (cond-> (contains? layer :detector/enabled?)
            (assoc :detector/enabled? (:detector/enabled? layer))))

      ;; fallthrough: treat unknown directives as :inherit
      accumulated)))

;------------------------------------------------------------------------------ Layer 1
;; Multi-layer resolution

(defn merge-config
  "Resolve a sequence of overlay layers into a final config map.

   Layers are applied left-to-right (base first, most-specific last).
   The base layer's `:config/params` and `:detector/enabled?` seed the
   accumulator. **Directives are only applied to overlay layers** —
   the base layer's `:config/directive` is recorded in
   `:config/directives` for audit but never executed (a base
   `:config/directive :disable` would be self-defeating; if a caller
   wants a disabled config they should set `:detector/enabled? false`
   on the base directly). Subsequent layers fold via `apply-directive`.

   Arguments:
     layers - seq of config maps. Each may have:
                :config/directive - :inherit | :disable | :enable | :tune
                                    (overlays only; base records but ignores)
                :config/params    - map of detector-specific knobs
                :detector/enabled? - explicit boolean (overrides directive for :tune)

   Returns: Resolved config map with:
     :detector/enabled? - final boolean (default true if never set)
     :config/params     - deeply merged tuning parameters
     :config/directives - vector of recorded directives (audit trail)

   Example:
     (merge-config
       [{:config/params {:window-size 10}}
        {:config/directive :tune :config/params {:window-size 20 :threshold 0.8}}
        {:config/directive :disable}
        {:config/directive :enable}])
     ;; => {:detector/enabled? true
     ;;     :config/params {:window-size 20 :threshold 0.8}
     ;;     :config/directives [:inherit :tune :disable :enable]}"
  [layers]
  (when (empty? layers)
    (throw (ex-info "merge-config requires at least one layer"
                    {:layers layers})))
  (let [;; The first layer is the base — its :config/params seed the
        ;; accumulator. Without this seeding the base's params get
        ;; dropped on its own implicit :inherit directive (see
        ;; merge-config-tune-test, overlay-test, deep-merge-nested-test
        ;; — all surfaced this when the base layer's params went
        ;; missing from the resolved config). Subsequent layers apply
        ;; via apply-directive normally.
        [base-layer & overlay-layers] layers
        seed (-> {:detector/enabled? (get base-layer :detector/enabled? true)
                  :config/params     (deep-merge {} (get base-layer :config/params {}))
                  :config/directives [(resolve-directive base-layer)]})
        resolved (reduce
                  (fn [acc layer]
                    (let [directive (resolve-directive layer)
                          acc'      (apply-directive acc layer)]
                      (update acc' :config/directives conj directive)))
                  seed
                  overlay-layers)]
    resolved))

;------------------------------------------------------------------------------ Layer 2
;; Query helpers

(defn enabled?
  "Return true if resolved config enables the detector.

   Defaults to true if :detector/enabled? is absent (safe default).

   Arguments:
     resolved-config - result of merge-config or apply-directive"
  [resolved-config]
  (get resolved-config :detector/enabled? true))

(defn effective-params
  "Return the merged :config/params map from a resolved config.

   Arguments:
     resolved-config - result of merge-config"
  [resolved-config]
  (get resolved-config :config/params {}))

(defn directives-applied
  "Return the ordered vector of directives that were applied.

   Arguments:
     resolved-config - result of merge-config"
  [resolved-config]
  (get resolved-config :config/directives []))

;------------------------------------------------------------------------------ Layer 2
;; Convenience: merge two configs (parent -> child)

(defn overlay
  "Overlay child-config onto parent-config using merge-config semantics.

   A shorthand for (merge-config [parent child]).

   Arguments:
     parent - base config map
     child  - overlay config map

   Returns: Resolved config map."
  [parent child]
  (merge-config [parent child]))

;------------------------------------------------------------------------------ Rich Comment
;; Rich comment

(comment
  ;; Inherit — child adds nothing
  (merge-config [{:config/params {:window-size 10}}
                 {:config/directive :inherit}])
  ;; => {:detector/enabled? true
  ;;     :config/params {:window-size 10}
  ;;     :config/directives [:inherit :inherit]}

  ;; Tune — merge params
  (merge-config [{:config/params {:window-size 10 :threshold 0.5}}
                 {:config/directive :tune
                  :config/params {:threshold 0.9 :extra "yes"}}])
  ;; => {:detector/enabled? true
  ;;     :config/params {:window-size 10 :threshold 0.9 :extra "yes"}
  ;;     :config/directives [:inherit :tune]}

  ;; Disable then re-enable
  (merge-config [{:config/params {:window-size 10}}
                 {:config/directive :disable}
                 {:config/directive :enable}])
  ;; => {:detector/enabled? true ...}

  ;; Disable wins if not overridden
  (enabled? (merge-config [{:config/params {}}
                            {:config/directive :disable}]))
  ;; => false

  ;; overlay shorthand
  (overlay {:config/params {:window-size 5}}
           {:config/directive :tune :config/params {:window-size 15}})
  ;; => {:detector/enabled? true :config/params {:window-size 15} ...}

  :leave-this-here)
