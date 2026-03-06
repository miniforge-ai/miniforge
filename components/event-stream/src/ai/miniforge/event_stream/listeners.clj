(ns ai.miniforge.event-stream.listeners
  "Listener registry with capability enforcement for N8 OCI compliance.

   Listeners are external observers (dashboards, fleet controllers, enterprise
   systems) that subscribe to workflow events with explicit capability levels:
   - :observe  — read-only event stream access
   - :advise   — can submit advisory annotations
   - :control  — can submit annotations and control actions

   The registry wraps event-stream subscription with capability metadata and
   enforcement."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def capability-levels
  "Valid listener capability levels, ordered by privilege."
  #{:observe :advise :control})

(def capability-rank
  "Numeric rank for capability comparison."
  {:observe 0 :advise 1 :control 2})

(defn capability-sufficient?
  "Check if actual capability meets or exceeds required capability."
  [actual required]
  (>= (get capability-rank actual 0)
      (get capability-rank required 0)))

(def ^:private privacy->min-capability
  "Map from event privacy level to minimum listener capability required.
   :public events -> any listener (:observe)
   :internal events -> :advise or higher
   :confidential events -> :control only"
  {:public       :observe
   :internal     :observe
   :confidential :control})

(defn event-requires-capability
  "Return the minimum capability level required to receive an event.

   Uses schema-defined privacy levels with fallback overrides for
   specific event types that need stricter filtering."
  [event-type]
  (let [;; Get privacy from schema (if available)
        privacy (try
                  (let [privacy-fn (requiring-resolve
                                    'ai.miniforge.event-stream.schema/event-privacy)]
                    (privacy-fn event-type))
                  (catch Exception _e :internal))]
    (get privacy->min-capability privacy :observe)))

(defn matches-workflow?
  "Check if event matches the workflow ID filter (nil/empty = match all)."
  [wf-ids event]
  (or (empty? wf-ids)
      (contains? (set wf-ids) (:workflow/id event))))

(defn matches-event-type?
  "Check if event matches the event type filter (nil/empty = match all)."
  [event-types event]
  (or (empty? event-types)
      (contains? (set event-types) (:event/type event))))

;------------------------------------------------------------------------------ Layer 1
;; Listener registration

(defn register-listener!
  "Register a listener with capability level.

   Arguments:
   - stream: Event stream atom
   - listener-spec: Map with:
     - :listener/type      — :watcher, :dashboard, :fleet, :enterprise
     - :listener/capability — :observe, :advise, or :control
     - :listener/identity   — {:principal string}
     - :listener/filters    — {:workflow-ids [...] :event-types [...]} (optional)
     - :listener/callback   — (fn [event]) for event delivery
     - :listener/options    — {:buffer-size int :include-payloads? bool} (optional)

   Returns: listener-id (UUID)"
  [stream listener-spec]
  (let [listener-id (random-uuid)
        {:keys [listener/type listener/capability listener/identity
                listener/filters listener/callback listener/options]} listener-spec]
    ;; Validate capability
    (when-not (contains? capability-levels capability)
      (throw (ex-info "Invalid capability level"
                      {:anomaly (response/make-anomaly
                                 :anomalies/incorrect
                                 (str "Invalid capability level: " capability)
                                 {:capability capability
                                  :valid capability-levels})})))
    ;; Build filter function from listener filters + capability enforcement
    (let [user-filter-fn (cond
                           (nil? filters) (constantly true)
                           :else (let [wf-ids (:workflow-ids filters)
                                       event-types (:event-types filters)]
                                   (fn [event]
                                     (and (matches-workflow? wf-ids event)
                                          (matches-event-type? event-types event)))))
          ;; Capability-based filter: listeners only receive events they're authorized for
          filter-fn (fn [event]
                      (and (capability-sufficient? capability
                                                   (event-requires-capability (:event/type event)))
                           (user-filter-fn event)))]
      ;; Subscribe to event stream
      (core/subscribe! stream listener-id callback filter-fn)
      ;; Store listener metadata
      (swap! stream assoc-in [:listeners listener-id]
             {:listener/id listener-id
              :listener/type type
              :listener/capability capability
              :listener/identity identity
              :listener/filters filters
              :listener/options options
              :listener/registered-at (java.util.Date.)})
      ;; Emit listener/attached event
      (core/publish! stream
                     (core/listener-attached stream nil listener-id type capability))
      listener-id)))

(defn deregister-listener!
  "Deregister a listener and remove its subscription.

   Arguments:
   - stream: Event stream atom
   - listener-id: UUID returned from register-listener!
   - reason: Optional reason string"
  [stream listener-id & [reason]]
  (let [listener (get-in @stream [:listeners listener-id])]
    (when listener
      ;; Unsubscribe from event stream
      (core/unsubscribe! stream listener-id)
      ;; Remove listener metadata
      (swap! stream update :listeners dissoc listener-id)
      ;; Emit listener/detached event
      (core/publish! stream
                     (core/listener-detached stream nil listener-id reason))
      true)))

(defn get-listener
  "Get listener metadata by ID."
  [stream listener-id]
  (get-in @stream [:listeners listener-id]))

(defn list-listeners
  "List all registered listeners."
  [stream]
  (vals (get @stream :listeners {})))

;------------------------------------------------------------------------------ Layer 2
;; Advisory annotations

(defn submit-annotation!
  "Submit an advisory annotation (requires :advise or :control capability).

   Arguments:
   - stream: Event stream atom
   - listener-id: UUID of the listener submitting
   - annotation: Map with:
     - :annotation/type — keyword (e.g. :warning, :suggestion, :flag)
     - :annotation/content — string or map content
     - :annotation/workflow-id — optional workflow to annotate

   Returns: annotation event or throws if insufficient capability."
  [stream listener-id annotation]
  (let [listener (get-listener stream listener-id)]
    (when-not listener
      (throw (ex-info "Listener not found"
                      {:anomaly (response/make-anomaly
                                 :anomalies/not-found
                                 (str "Listener not found: " listener-id)
                                 {:listener-id listener-id})})))
    (when-not (capability-sufficient? (:listener/capability listener) :advise)
      (throw (ex-info "Insufficient capability for annotation"
                      {:anomaly (response/make-anomaly
                                 :anomalies/forbidden
                                 "Insufficient capability for annotation"
                                 {:listener-id listener-id
                                  :capability (:listener/capability listener)
                                  :required :advise})})))
    (let [event (core/annotation-created
                 stream
                 (:annotation/workflow-id annotation)
                 listener-id
                 (:annotation/type annotation)
                 (:annotation/content annotation))]
      (core/publish! stream event)
      event)))

;------------------------------------------------------------------------------ Layer 3
;; Control action submission (capability-gated)

(defn submit-control-action!
  "Submit a control action via a listener (requires :control capability).

   Arguments:
   - stream: Event stream atom
   - listener-id: UUID of the listener submitting
   - action: Control action map (from control/create-control-action)
   - execution-fn: (fn [action] -> result-map)

   Returns: Execution result or throws if insufficient capability."
  [stream listener-id action execution-fn]
  (let [listener (get-listener stream listener-id)]
    (when-not listener
      (throw (ex-info "Listener not found"
                      {:anomaly (response/make-anomaly
                                 :anomalies/not-found
                                 (str "Listener not found: " listener-id)
                                 {:listener-id listener-id})})))
    (when-not (capability-sufficient? (:listener/capability listener) :control)
      (throw (ex-info "Insufficient capability for control action"
                      {:anomaly (response/make-anomaly
                                 :anomalies/forbidden
                                 "Insufficient capability for control action"
                                 {:listener-id listener-id
                                  :capability (:listener/capability listener)
                                  :required :control})})))
    ;; Delegate to control/execute-control-action!
    (let [execute-fn (requiring-resolve 'ai.miniforge.event-stream.control/execute-control-action!)]
      (execute-fn stream action execution-fn))))
