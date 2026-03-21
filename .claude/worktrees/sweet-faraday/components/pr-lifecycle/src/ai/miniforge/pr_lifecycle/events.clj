(ns ai.miniforge.pr-lifecycle.events
  "PR lifecycle events.

   Events are the communication mechanism between the PR controller
   and the DAG scheduler. Each event represents a state change in the
   PR lifecycle that the scheduler needs to react to."
  (:require
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Event types

(def event-types
  "Valid PR lifecycle event types."
  #{:pr/opened           ; PR successfully created
    :pr/ci-passed        ; CI checks passed
    :pr/ci-failed        ; CI checks failed
    :pr/review-approved  ; Required approvals received
    :pr/review-changes-requested ; Reviewer requested changes
    :pr/comment-actionable ; Actionable comment added
    :pr/merged           ; PR successfully merged
    :pr/closed           ; PR closed without merge
    :pr/rebase-needed    ; Base branch moved, rebase required
    :pr/conflict         ; Merge conflict detected
    :pr/fix-pushed})     ; Fix commit pushed

;------------------------------------------------------------------------------ Layer 0
;; Event constructors

(defn create-event
  "Create a PR lifecycle event.

   Arguments:
   - type: Event type keyword (from event-types)
   - data: Event-specific data

   Required data keys:
   - :dag/id - DAG run ID
   - :run/id - Run instance ID
   - :task/id - Task ID
   - :pr/id - PR identifier (number or ID)

   Optional data keys:
   - :pr/url - PR URL
   - :pr/sha - Current HEAD SHA
   - :ci/logs - CI log summary (for failures)
   - :review/comments - Review comments (for changes requested)
   - :error - Error details"
  [type data]
  (merge
   {:event/id (random-uuid)
    :event/type type
    :event/timestamp (java.util.Date.)}
   data))

(defn pr-opened
  "Create a PR opened event."
  [dag-id run-id task-id pr-id pr-url branch sha]
  (create-event :pr/opened
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :pr/url pr-url
                 :pr/branch branch
                 :pr/sha sha}))

(defn ci-passed
  "Create a CI passed event."
  [dag-id run-id task-id pr-id sha]
  (create-event :pr/ci-passed
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :pr/sha sha}))

(defn ci-failed
  "Create a CI failed event."
  [dag-id run-id task-id pr-id sha logs]
  (create-event :pr/ci-failed
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :pr/sha sha
                 :ci/logs logs}))

(defn review-approved
  "Create a review approved event."
  [dag-id run-id task-id pr-id approvers]
  (create-event :pr/review-approved
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :review/approvers approvers}))

(defn review-changes-requested
  "Create a changes requested event."
  [dag-id run-id task-id pr-id comments]
  (create-event :pr/review-changes-requested
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :review/comments comments}))

(defn comment-actionable
  "Create an actionable comment event."
  [dag-id run-id task-id pr-id comment-data]
  (create-event :pr/comment-actionable
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :comment comment-data}))

(defn merged
  "Create a merged event."
  [dag-id run-id task-id pr-id merge-sha]
  (create-event :pr/merged
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :pr/merge-sha merge-sha}))

(defn closed
  "Create a closed (without merge) event."
  [dag-id run-id task-id pr-id reason]
  (create-event :pr/closed
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :close/reason reason}))

(defn rebase-needed
  "Create a rebase needed event."
  [dag-id run-id task-id pr-id base-sha]
  (create-event :pr/rebase-needed
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :pr/base-sha base-sha}))

(defn conflict
  "Create a conflict detected event."
  [dag-id run-id task-id pr-id conflicting-files]
  (create-event :pr/conflict
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :conflict/files conflicting-files}))

(defn fix-pushed
  "Create a fix pushed event."
  [dag-id run-id task-id pr-id sha fix-type]
  (create-event :pr/fix-pushed
                {:dag/id dag-id
                 :run/id run-id
                 :task/id task-id
                 :pr/id pr-id
                 :pr/sha sha
                 :fix/type fix-type})) ; :ci-fix :review-fix :conflict-fix

;------------------------------------------------------------------------------ Layer 1
;; Event channel/bus

(defn create-event-bus
  "Create an event bus for publishing and subscribing to events.

   Returns an atom containing:
   - :events - Vector of all events
   - :subscribers - Map of subscriber-id -> callback fn
   - :filters - Map of subscriber-id -> filter fn"
  []
  (atom {:events []
         :subscribers {}
         :filters {}}))

(defn publish!
  "Publish an event to the event bus.
   Notifies all subscribers whose filters match."
  [event-bus event logger]
  (let [{:keys [subscribers filters]} @event-bus]
    ;; Add to event log
    (swap! event-bus update :events conj event)
    ;; Notify matching subscribers
    (doseq [[sub-id callback] subscribers]
      (let [filter-fn (get filters sub-id (constantly true))]
        (when (filter-fn event)
          (try
            (callback event)
            (catch Exception e
              (when logger
                (log/error logger :pr-lifecycle :event/callback-error
                           {:message "Event callback failed"
                            :data {:subscriber-id sub-id
                                   :event-type (:event/type event)
                                   :error (.getMessage e)}})))))))
    (when logger
      (log/debug logger :pr-lifecycle :event/published
                 {:message "Event published"
                  :data {:event-type (:event/type event)
                         :task-id (:task/id event)
                         :subscriber-count (count subscribers)}}))
    event))

(defn subscribe!
  "Subscribe to events on the event bus.

   Arguments:
   - event-bus: The event bus atom
   - subscriber-id: Unique identifier for this subscriber
   - callback: Function (fn [event] ...) called for matching events
   - filter-fn: Optional predicate (fn [event] bool) to filter events

   Returns subscriber-id."
  ([event-bus subscriber-id callback]
   (subscribe! event-bus subscriber-id callback (constantly true)))
  ([event-bus subscriber-id callback filter-fn]
   (swap! event-bus
          (fn [bus]
            (-> bus
                (assoc-in [:subscribers subscriber-id] callback)
                (assoc-in [:filters subscriber-id] filter-fn))))
   subscriber-id))

(defn unsubscribe!
  "Unsubscribe from events."
  [event-bus subscriber-id]
  (swap! event-bus
         (fn [bus]
           (-> bus
               (update :subscribers dissoc subscriber-id)
               (update :filters dissoc subscriber-id))))
  nil)

(defn events-for-task
  "Get all events for a specific task."
  [event-bus task-id]
  (->> (:events @event-bus)
       (filter #(= task-id (:task/id %)))
       vec))

(defn events-for-pr
  "Get all events for a specific PR."
  [event-bus pr-id]
  (->> (:events @event-bus)
       (filter #(= pr-id (:pr/id %)))
       vec))

(defn latest-event
  "Get the most recent event matching a predicate."
  [event-bus pred]
  (->> (:events @event-bus)
       (filter pred)
       last))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create events
  (def dag-id (random-uuid))
  (def run-id (random-uuid))
  (def task-id (random-uuid))

  (def opened-event
    (pr-opened dag-id run-id task-id 123 "https://github.com/foo/bar/pull/123"
               "feat/my-feature" "abc123"))

  (:event/type opened-event)  ; => :pr/opened

  (def failed-event
    (ci-failed dag-id run-id task-id 123 "abc123"
               "Test failed: foo_test.clj line 42"))

  ;; Event bus
  (def bus (create-event-bus))

  ;; Subscribe to CI events
  (subscribe! bus :ci-monitor
              (fn [event] (println "CI event:" (:event/type event)))
              (fn [event] (#{:pr/ci-passed :pr/ci-failed} (:event/type event))))

  ;; Publish events
  (publish! bus opened-event nil)  ; Not delivered (filtered out)
  (publish! bus failed-event nil)  ; Delivered to :ci-monitor

  ;; Query events
  (events-for-task bus task-id)
  (events-for-pr bus 123)

  :leave-this-here)
