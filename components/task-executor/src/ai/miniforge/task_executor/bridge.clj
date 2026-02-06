(ns ai.miniforge.task-executor.bridge
  "Pure event translation between PR lifecycle and DAG scheduler vocabularies.

  Maps PR event types (e.g., :pr/ci-passed) to scheduler actions (e.g., :ci-passed).
  This enables the PR lifecycle controller to communicate state changes back to the
  DAG scheduler without coupling the two components.")

(def pr-event->scheduler-action
  "Map of PR lifecycle event types to DAG scheduler action keywords.

  Events without mappings (e.g., :pr/comment-actionable) are handled internally
  by the PR controller and don't require scheduler notification.

  Conflict and rebase events map to :ci-failed to reuse existing retry logic."
  {:pr/opened                    :pr-opened
   :pr/ci-passed                 :ci-passed
   :pr/ci-failed                 :ci-failed
   :pr/review-approved           :review-approved
   :pr/review-changes-requested  :review-changes-requested
   :pr/fix-pushed                :fix-pushed
   :pr/merged                    :merged
   :pr/closed                    :merge-failed
   :pr/conflict                  :ci-failed     ; reuse retry logic
   :pr/rebase-needed             :ci-failed})   ; reuse retry logic

(defn translate-event
  "Translate a PR lifecycle event to a scheduler event.

  Input: PR event map with :event/type and :task-id
  Output: Scheduler event map with :event/action and :event/task-id, or nil if unmapped

  Example:
    (translate-event {:event/type :pr/ci-passed :task-id \"task-123\"})
    => {:event/action :ci-passed :event/task-id \"task-123\"}"
  [{:keys [event/type task-id] :as pr-event}]
  (when-let [action (get pr-event->scheduler-action type)]
    {:event/action action
     :event/task-id task-id
     :timestamp (:timestamp pr-event)
     :metadata (dissoc pr-event :event/type :task-id :timestamp)}))

(defn create-scheduler-event
  "Create a scheduler event map from components.

  Args:
    task-id: Task identifier string
    action: Scheduler action keyword (e.g., :ci-passed)
    opts: Optional map of additional fields (timestamp, metadata)

  Returns: Event map suitable for scheduler/handle-task-event"
  [task-id action & [opts]]
  (merge
    {:event/action action
     :event/task-id task-id
     :timestamp (or (:timestamp opts) (java.time.Instant/now))}
    (when-let [metadata (:metadata opts)]
      {:metadata metadata})))

(defn unmapped-event?
  "Return true if the PR event type has no scheduler mapping."
  [event-type]
  (nil? (get pr-event->scheduler-action event-type)))
