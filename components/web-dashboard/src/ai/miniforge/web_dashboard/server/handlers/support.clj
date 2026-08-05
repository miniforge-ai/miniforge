;; Copyright 2025 miniforge.ai
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
(ns ai.miniforge.web-dashboard.server.handlers.support
  "Low-level helpers for the dashboard HTTP handlers: anomaly/response
   construction, constants, filter/facet projection, and control-action
   shaping. Depends on no sibling handler namespace."
  (:require
   [cheshire.core :as json]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.filters-new :as filters-new]))

;------------------------------------------------------------------------------ Layer 0

;; Anomaly/response helpers
;;
;; W2 convergence: producer sites in this brick emit canonical
;; `ai.miniforge.anomaly` maps. The brick's HTTP boundary
;; (`anomaly-http-response`) routes through `response/anomaly->http-response`
;; whose status + user-message tables are still keyed by legacy
;; `:anomalies/*` keywords (translate's `dispatch-key` prefers
;; `:anomaly/subtype` for routing — see #1001). To preserve cross-brick
;; HTTP translation behavior during the convergence, callers pass a
;; legacy `:anomalies/*` category which is mapped to its canonical
;; generic `:anomaly/type` AND carried verbatim under `:anomaly/subtype`.
;; Both are removed once `response/translate` is reshaped to dispatch on
;; canonical generic types directly (anomaly-convergence W5).
(def ^{:stratum 0} ^:private legacy-category->canonical-type
  "Map of legacy `:anomalies/*` categories used at this brick's call
   sites to the canonical generic `:anomaly/type`. The eight cognitect-
   standard rows that the convergence runbook maps 1:1 to a generic
   type — only the four this brick actually emits are listed. Removed
   in W5 once call sites pass canonical types directly."
  {:anomalies/incorrect :invalid-input
   :anomalies/not-found :not-found
   :anomalies/forbidden :unauthorized
   :anomalies/fault     :fault})

(def ^{:stratum 0} ^:private exception-fallback-subtype
  "Legacy `:anomalies/*` category carried as `:anomaly/subtype` on
   exception-derived anomalies so the HTTP translate tables (still
   keyed by `:anomalies/*`) return the canonical 500/`internal error`
   status + message for unexpected exceptions caught at boundaries."
  :anomalies/fault)

(defn ^{:stratum 0} response-success?
  "Check if a response builder result represents success."
  [response]
  (response/success? response))

(defn ^{:stratum 0} anomaly-http-response
  "Translate an anomaly map to a Ring HTTP response.
   Body is JSON-encoded for wire transport."
  [anomaly-map]
  (let [raw (response/anomaly->http-response anomaly-map)]
    (update raw :body json/generate-string)))

;; Constants
(def ^{:stratum 0} workflow-detail-event-limit
  "Maximum number of events to load per workflow detail view or panel."
  200)

(def ^{:stratum 0} default-dashboard-requester
  "The requester identity the dashboard stamps on every control action.
   NOT a fallback for a missing body field: a body-supplied requester is
   ignored outright, never preferred — see `command-requester` and
   `build-control-action` (miniforge#1460), where honouring it would let
   an unauthenticated caller poison the audit trail or self-authorize.
   The surface itself is the only honest attribution until an
   authenticated session identity is threaded through, at which point
   this constant gives way to that identity."
  {:principal "dashboard" :role :operator})

(def ^{:stratum 0} control-intervention-by-command
  "The three run controls this console offers, mapped to the
   intervention verbs the operator channel understands. The legacy
   command name `stop` is gone with the `.edn` poller: the vocabulary
   calls the same operation `cancel`. Anything not in this table is
   rejected at the boundary instead of being written and silently
   dropped downstream."
  {"pause" :pause
   "resume" :resume
   "cancel" :cancel})

;; Filter helpers
(defn ^{:stratum 0} maybe-apply-filters
  [items filter-ast pane]
  (if filter-ast
    (filters-new/apply-filters items filter-ast pane)
    items))

(defn ^{:stratum 0} filtered-activity
  [state trains filter-ast]
  (let [activities (state/get-recent-activity state)]
    (if filter-ast
      (let [allowed-train-ids (set (keep :train/id trains))]
        (filter #(contains? allowed-train-ids (:train-id %)) activities))
      activities)))

(defn ^{:stratum 0} pane-data
  "Get pane data used for facet computation/filtering."
  [state pane]
  (case pane
    :task-status (:tasks (state/get-dag-state state))
    :workflows (state/get-workflows state)
    :evidence (let [es (state/get-evidence-state state)]
                 (concat (:trains es) (:workflows es)))
    :fleet (:trains (state/get-fleet-state state))
    []))

(defn ^{:stratum 0} normalize-facet-counts
  "Normalize facet output into a map."
  [facets]
  (cond
    (map? facets) facets
    (sequential? facets) (into {} facets)
    :else {}))

;; Evidence/Artifact id helper (N5)
(defn ^{:stratum 0} artifact-id-value
  "Normalize a URI artifact id for the artifact store: a UUID-shaped
   string becomes a UUID (stores that key by UUID need the typed value),
   any other string is passed through unchanged so a string-keyed store
   can still find it. `parse-uuid` returns nil — it does NOT throw — on a
   non-UUID string, so `or` preserves the original id rather than
   collapsing it to nil."
  [artifact-id]
  (if (string? artifact-id)
    (or (parse-uuid artifact-id) artifact-id)
    artifact-id))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} canonical-type
  "Resolve a category keyword to its canonical `:anomaly/type`. Passes
   canonical type keywords through unchanged so call sites can adopt
   the canonical name immediately."
  [category-or-type]
  (or (get legacy-category->canonical-type category-or-type)
      category-or-type))

(defn ^{:stratum 1} from-exception
  "Convert an exception to a canonical anomaly map of type `:fault`,
   preserving the exception class + message + ex-data under
   `:anomaly/data`. Nil exception message falls back to the class
   name per the W2 batch-2 precedent in #1003.

   `:anomaly/subtype` is set to `:anomalies/fault` so the HTTP
   translate boundary (legacy-keyword-keyed during convergence) still
   resolves to 500 + the canonical internal-error user message."
  [^Throwable e]
  (assoc (anomaly/exception-anomaly :fault
                                    (or (ex-message e) (.getName (class e)))
                                    e)
         :anomaly/subtype exception-fallback-subtype))

(defn ^{:stratum 1} filtered-fleet-trains
  [state filter-ast]
  (maybe-apply-filters (:trains (state/get-fleet-state state)) filter-ast :fleet))

(defn ^{:stratum 1} filtered-workflow-runs
  [state filter-ast]
  (maybe-apply-filters (state/get-workflows state) filter-ast :workflows))

(defn ^{:stratum 1} compute-global-facets
  "Compute facet counts for global filters across all applicable panes."
  [state global-filters]
  (into {}
        (map (fn [spec]
               (let [filter-id (:filter/id spec)
                     per-pane (for [pane (sort (:filter/applicable-to spec))]
                                (normalize-facet-counts
                                 (filters-new/compute-facets (pane-data state pane) filter-id pane)))
                     merged (apply merge-with + (cons {} per-pane))
                     top-facets (->> merged
                                     (sort-by val >)
                                     (take 40))]
                 [filter-id top-facets]))
             global-filters)))

(defn ^{:stratum 1} command-requester
  "Principal recorded on the intervention. Derived SERVER-SIDE ONLY.

   This endpoint has no authenticated identity yet (see miniforge#1460),
   so a body-supplied `:action/requester` is UNTRUSTED and ignored:
   honouring it would let any caller stamp an arbitrary
   `:intervention/requested-by` and poison the audit trail
   (`{\"command\":\"cancel\",\"action\":{\"requester\":{\"principal\":\"…\"}}}`).
   Until an authenticated session identity is threaded here, the surface
   itself is the only honest attribution."
  []
  (:principal default-dashboard-requester))

;; Structured control action builder (N8)
(defn ^{:stratum 1} build-control-action
  "Build a control action map from parsed request data.

   The requester is derived SERVER-SIDE and the body's
   `:action/requester` is ignored — same rule, and same reason, as
   `command-requester` (miniforge#1460). Trusting it here would let a
   caller both spoof the `:control-action/*` audit identity AND name the
   role that `authorize-action` checks, so an unauthenticated request
   could self-authorize. Until an authenticated session identity is
   threaded through, the surface is the only honest requester."
  [data workflow-id]
  (event-stream/create-control-action
   (keyword (:action/type data))
   {:target-type :workflow :target-id workflow-id}
   default-dashboard-requester
   {:justification (:action/justification data)
    :parameters (:action/parameters data)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} make-anomaly
  "Create a canonical anomaly map.

   `category-or-type` accepts either the legacy `:anomalies/*` keyword
   used pre-flip or the canonical `:anomaly/type` keyword directly.
   When a legacy keyword is supplied, the canonical generic type is
   set under `:anomaly/type` AND the legacy keyword is preserved
   verbatim under `:anomaly/subtype` — `response/translate` dispatches
   on subtype first, so the HTTP boundary keeps returning the right
   status + user message during the convergence (translate tables are
   still keyed by `:anomalies/*`). When a canonical type is supplied
   directly the result carries no subtype."
  [category-or-type message & [context]]
  (let [a-type (canonical-type category-or-type)
        ctx (or context {})]
    (if (contains? legacy-category->canonical-type category-or-type)
      (anomaly/sub-anomaly a-type category-or-type message ctx)
      (anomaly/anomaly a-type message ctx))))
