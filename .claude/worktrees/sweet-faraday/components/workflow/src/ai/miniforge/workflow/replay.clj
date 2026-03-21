(ns ai.miniforge.workflow.replay
  "Event stream replay for workflow state reconstruction.
   Enables reproducibility and time-travel debugging.
   
   Layer 0: Pure functions for event filtering and sorting
   Layer 1: State reconstruction from events
   Layer 2: Replay execution and verification"
  )

;------------------------------------------------------------------------------ Layer 0
;; Event filtering and sorting

(defn filter-events-by-workflow
  "Filter events for a specific workflow ID."
  [events workflow-id]
  (filter #(= workflow-id (get-in % [:data :workflow-id])) events))

(defn filter-events-by-time-range
  "Filter events within a time range."
  [events start-time end-time]
  (filter
   (fn [event]
     (let [ts (:log/timestamp event)]
       (and ts
            (or (nil? start-time) (not (.before ts start-time)))
            (or (nil? end-time) (not (.after ts end-time))))))
   events))

(defn sort-events-by-timestamp
  "Sort events chronologically by timestamp."
  [events]
  (sort-by :log/timestamp events))

;------------------------------------------------------------------------------ Layer 1
;; State reconstruction from events

(defn replay-event
  "Apply a single event to workflow state.
   Returns updated state or unchanged state if event doesn't affect state."
  [workflow-state event]
  (let [event-type (:log/event event)
        event-data (:data event)]
    (case event-type
      :workflow/started
      (assoc workflow-state
             :workflow/id (:workflow-id event-data)
             :workflow/status :executing
             :workflow/started-at (:log/timestamp event))

      :workflow/phase-started
      (-> workflow-state
          (assoc :workflow/current-phase (:phase event-data))
          (assoc-in [:workflow/phases (:phase event-data) :status] :executing)
          (assoc-in [:workflow/phases (:phase event-data) :started-at] (:log/timestamp event)))

      :workflow/phase-completed
      (-> workflow-state
          (assoc-in [:workflow/phases (:phase event-data) :status] :completed)
          (assoc-in [:workflow/phases (:phase event-data) :completed-at] (:log/timestamp event)))

      :workflow/phase-failed
      (-> workflow-state
          (assoc-in [:workflow/phases (:phase event-data) :status] :failed)
          (assoc-in [:workflow/phases (:phase event-data) :error] (:error event-data)))

      :workflow/completed
      (assoc workflow-state
             :workflow/status :completed
             :workflow/completed-at (:log/timestamp event))

      :workflow/failed
      (assoc workflow-state
             :workflow/status :failed
             :workflow/failed-at (:log/timestamp event)
             :workflow/error (:error event-data))

      ;; Event doesn't affect state, return unchanged
      workflow-state)))

(defn replay-events
  "Replay a sequence of events to reconstruct workflow state.
   
   Arguments:
   - initial-state - Starting workflow state (or empty map)
   - events - Sequence of log events
   
   Returns reconstructed workflow state."
  [initial-state events]
  (reduce replay-event initial-state events))

;------------------------------------------------------------------------------ Layer 2
;; Replay execution

(defn replay-workflow
  "Reconstruct workflow state from event stream.
   
   Arguments:
   - events - All events for this workflow
   - & opts   - Keyword options:
     :workflow-id - Filter to specific workflow (optional)
     :until       - Replay only up to this timestamp (optional)
   
   Returns:
   {:state workflow-state
    :events-applied int
    :final-status keyword}"
  [events & {:keys [workflow-id until]}]
  (let [;; Filter events
        filtered-events (cond->> events
                          workflow-id (filter #(= workflow-id (get-in % [:data :workflow-id])))
                          until (filter #(let [ts (:log/timestamp %)]
                                          (or (nil? until) (not (.after ts until)))))
                          true sort-events-by-timestamp)
        
        ;; Replay
        initial-state {}
        final-state (replay-events initial-state filtered-events)]
    
    {:state final-state
     :events-applied (count filtered-events)
     :final-status (:workflow/status final-state)}))

(defn verify-determinism
  "Verify that replaying events produces the same state.
   
   Arguments:
   - events - Event stream
   - expected-state - Expected final state
   - opts - Options for replay
   
   Returns:
   {:deterministic? boolean
    :differences [...] ; If not deterministic
    :replayed-state map}"
  [events expected-state & opts]
  (let [replay-result (apply replay-workflow events opts)
        replayed-state (:state replay-result)
        ;; Compare key fields (ignore timestamps, intermediate data)
        compare-keys [:workflow/id :workflow/status :workflow/current-phase]
        differences (reduce
                     (fn [acc k]
                       (let [expected-val (get expected-state k)
                             replayed-val (get replayed-state k)]
                         (if (= expected-val replayed-val)
                           acc
                           (conj acc {:key k
                                      :expected expected-val
                                      :replayed replayed-val}))))
                     []
                     compare-keys)]
    {:deterministic? (empty? differences)
     :differences differences
     :replayed-state replayed-state
     :events-applied (:events-applied replay-result)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example event stream
  (def sample-events
    [{:log/id (random-uuid)
      :log/timestamp #inst "2026-01-24T10:00:00.000-00:00"
      :log/level :info
      :log/category :workflow
      :log/event :workflow/started
      :data {:workflow-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
             :spec-title "Test Workflow"}}
     {:log/id (random-uuid)
      :log/timestamp #inst "2026-01-24T10:00:01.000-00:00"
      :log/level :info
      :log/category :workflow
      :log/event :workflow/phase-started
      :data {:workflow-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
             :phase :plan}}
     {:log/id (random-uuid)
      :log/timestamp #inst "2026-01-24T10:00:10.000-00:00"
      :log/level :info
      :log/category :workflow
      :log/event :workflow/phase-completed
      :data {:workflow-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
             :phase :plan}}])

  ;; Replay events
  (replay-workflow sample-events
                   :workflow-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
  ;; => {:state {...} :events-applied 3 :final-status :executing}

  ;; Partial replay (up to specific time)
  (replay-workflow sample-events
                   :workflow-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                   :until #inst "2026-01-24T10:00:05.000-00:00")
  ;; => {:state {...} :events-applied 2 :final-status :executing}

  :leave-this-here)
