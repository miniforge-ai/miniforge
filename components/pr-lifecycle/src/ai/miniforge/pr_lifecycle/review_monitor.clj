(ns ai.miniforge.pr-lifecycle.review-monitor
  "Review and comment monitoring for PRs.

   Polls GitHub for review status and new comments,
   emits events when reviews are submitted or actionable comments added."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.triage :as triage]
   [ai.miniforge.logging.interface :as log]
   [babashka.process :as process]
   [clojure.set :as set]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Review status types

(def review-states
  "Possible review states."
  #{:pending           ; Awaiting reviews
    :approved          ; Required approvals met
    :changes-requested ; Reviewer requested changes
    :commented         ; Review submitted with comments only
    :dismissed})       ; Review was dismissed

;------------------------------------------------------------------------------ Layer 0
;; GitHub CLI helpers

(defn- run-gh-command
  "Run a gh CLI command and return result."
  [args worktree-path]
  (try
    (let [result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        args)]
      (if (zero? (:exit result))
        (dag/ok {:output (str/trim (:out result ""))})
        (dag/err :gh-command-failed
                 (str/trim (:err result ""))
                 {:exit-code (:exit result)})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

(defn get-pr-reviews
  "Get reviews for a PR.

   Returns result with review information."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json"
                 "reviews,reviewDecision"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:raw (:output (:data result))})
      result)))

(defn get-pr-comments
  "Get comments on a PR (both review comments and issue comments).

   Returns result with comments."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json"
                 "comments,reviewComments"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:raw (:output (:data result))})
      result)))

;------------------------------------------------------------------------------ Layer 1
;; Review status computation

(defn parse-review-decision
  "Parse GitHub review decision into our status.

   reviewDecision can be: APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED, or empty"
  [review-decision]
  (case (str/upper-case (or review-decision ""))
    "APPROVED" :approved
    "CHANGES_REQUESTED" :changes-requested
    "REVIEW_REQUIRED" :pending
    :pending))

(defn compute-review-status
  "Compute overall review status from reviews.

   Arguments:
   - reviews: Sequence of review maps with :state :author etc.
   - required-approvals: Number of required approvals (default 1)

   Returns {:status keyword :approvers [...] :changes-requested-by [...]}"
  [reviews required-approvals]
  (let [by-state (group-by #(keyword (str/lower-case (or (:state %) ""))) reviews)
        approved (get by-state :approved [])
        changes-requested (get by-state :changes_requested [])

        approvers (mapv :author approved)
        requesters (mapv :author changes-requested)

        ;; Most recent changes_requested takes precedence over earlier approvals
        ;; from the same reviewer
        unique-approvers (set/difference
                          (set approvers)
                          (set requesters))

        status (cond
                 (seq changes-requested) :changes-requested
                 (>= (count unique-approvers) required-approvals) :approved
                 :else :pending)]

    {:status status
     :approvers (vec unique-approvers)
     :changes-requested-by requesters
     :reviews reviews
     :approval-count (count unique-approvers)
     :required-approvals required-approvals}))

(defn extract-review-comments
  "Extract actionable comments from reviews.

   Returns sequence of comment maps."
  [reviews]
  (->> reviews
       (filter #(= "CHANGES_REQUESTED" (str/upper-case (or (:state %) ""))))
       (mapcat :comments)
       (map (fn [c]
              {:body (:body c)
               :author (:author c)
               :path (:path c)
               :line (:line c)}))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; Comment tracking

(defn create-comment-tracker
  "Create a tracker for PR comments.

   Tracks seen comments to detect new ones."
  []
  (atom {:seen-ids #{}
         :comments []}))

(defn track-comment!
  "Track a comment, returning true if it's new."
  [tracker comment]
  (let [comment-id (or (:id comment)
                       (hash (str (:body comment) (:author comment) (:path comment))))
        already-seen? (contains? (:seen-ids @tracker) comment-id)]
    (when-not already-seen?
      (swap! tracker
             (fn [t]
               (-> t
                   (update :seen-ids conj comment-id)
                   (update :comments conj comment)))))
    (not already-seen?)))

(defn new-comments
  "Get comments that are new since last check.

   Arguments:
   - tracker: Comment tracker
   - comments: Current comments from PR

   Returns sequence of new comments."
  [tracker comments]
  (->> comments
       (filter #(track-comment! tracker %))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; Monitoring

(defn create-review-monitor
  "Create a review monitor for a PR.

   Arguments:
   - dag-id: DAG run ID
   - run-id: Run instance ID
   - task-id: Task ID
   - pr-number: PR number
   - worktree-path: Path to git worktree

   Options:
   - :poll-interval-ms - Polling interval (default 30000)
   - :required-approvals - Number of required approvals (default 1)
   - :event-bus - Event bus for publishing events
   - :triage-policy - Comment triage policy (default :actionable-only)

   Returns monitor state atom."
  [dag-id run-id task-id pr-number worktree-path
   & {:keys [poll-interval-ms required-approvals event-bus triage-policy]
      :or {poll-interval-ms 30000 required-approvals 1 triage-policy :actionable-only}}]
  (atom {:dag-id dag-id
         :run-id run-id
         :task-id task-id
         :pr-number pr-number
         :worktree-path worktree-path
         :poll-interval-ms poll-interval-ms
         :required-approvals required-approvals
         :event-bus event-bus
         :triage-policy triage-policy
         :status :pending
         :comment-tracker (create-comment-tracker)
         :started-at nil
         :last-poll nil
         :polls 0
         :running? false}))

(defn poll-review-status
  "Poll review status once.

   Arguments:
   - monitor: Monitor state atom
   - logger: Optional logger

   Returns {:status keyword :reviews {...} :new-comments [...] :events [...]}"
  [monitor logger]
  (let [{:keys [dag-id run-id task-id pr-number worktree-path
                required-approvals comment-tracker triage-policy]} @monitor
        reviews-result (get-pr-reviews worktree-path pr-number)
        comments-result (get-pr-comments worktree-path pr-number)]

    (swap! monitor assoc
           :last-poll (java.util.Date.)
           :polls (inc (:polls @monitor 0)))

    (if (or (dag/err? reviews-result) (dag/err? comments-result))
      (do
        (when logger
          (log/warn logger :pr-lifecycle :review/poll-failed
                    {:message "Failed to poll review status"
                     :data {:pr-number pr-number}}))
        {:status :unknown :error "Poll failed"})

      (let [;; Parse reviews (simplified - real impl would parse JSON)
            reviews []  ; Would parse from reviews-result
            computed (compute-review-status reviews required-approvals)
            status (:status computed)
            prev-status (:status @monitor)

            ;; Check for new comments
            comments [] ; Would parse from comments-result
            new-comments (new-comments comment-tracker comments)

            ;; Triage new comments
            triaged (when (seq new-comments)
                      (triage/triage-comments new-comments :policy triage-policy))
            actionable-comments (:actionable triaged)

            ;; Generate events
            events (cond-> []
                     ;; Status changed to approved
                     (and (not= prev-status :approved)
                          (= status :approved))
                     (conj (events/review-approved dag-id run-id task-id pr-number
                                                   (:approvers computed)))

                     ;; Status changed to changes-requested
                     (and (not= prev-status :changes-requested)
                          (= status :changes-requested))
                     (conj (events/review-changes-requested
                            dag-id run-id task-id pr-number
                            (extract-review-comments reviews)))

                     ;; New actionable comments
                     (seq actionable-comments)
                     (into (map #(events/comment-actionable
                                  dag-id run-id task-id pr-number %)
                                actionable-comments)))]

        ;; Update monitor state
        (swap! monitor assoc :status status :last-review computed)

        (when (and logger (seq events))
          (log/info logger :pr-lifecycle :review/events-generated
                    {:message "Review events generated"
                     :data {:pr-number pr-number
                            :event-count (count events)}}))

        {:status status
         :reviews computed
         :new-comments new-comments
         :actionable-comments actionable-comments
         :events events}))))

(defn run-review-monitor
  "Run the review monitor until approved or changes requested.

   Note: This doesn't necessarily terminate - it runs until
   the controller decides to stop it (e.g., after generating fix).

   Arguments:
   - monitor: Monitor state atom
   - logger: Optional logger

   Options:
   - :on-poll - Callback (fn [poll-result]) after each poll
   - :stop-on-status - Set of statuses that stop monitoring (default #{:approved})

   Returns final status map."
  [monitor logger & {:keys [on-poll stop-on-status]
                     :or {stop-on-status #{:approved}}}]
  (let [{:keys [poll-interval-ms event-bus]} @monitor]

    (swap! monitor assoc :running? true :started-at (java.util.Date.))

    (when logger
      (log/info logger :pr-lifecycle :review/monitor-started
                {:message "Review monitor started"
                 :data {:pr-number (:pr-number @monitor)}}))

    (loop []
      (let [poll-result (poll-review-status monitor logger)]

        ;; Call poll callback
        (when on-poll
          (on-poll poll-result))

        ;; Publish events
        (when event-bus
          (doseq [event (:events poll-result)]
            (events/publish! event-bus event logger)))

        (cond
          ;; Stop on specified status
          (contains? stop-on-status (:status poll-result))
          (do
            (swap! monitor assoc :running? false)
            poll-result)

          ;; Monitor stopped externally
          (not (:running? @monitor))
          poll-result

          ;; Continue polling
          :else
          (do
            (Thread/sleep poll-interval-ms)
            (recur)))))))

(defn stop-review-monitor
  "Stop a running review monitor."
  [monitor]
  (swap! monitor assoc :running? false))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a review monitor
  (def monitor
    (create-review-monitor
     (random-uuid) (random-uuid) (random-uuid)
     123 "/path/to/repo"
     :required-approvals 2))

  ;; Poll once
  (poll-review-status monitor nil)

  ;; Compute review status
  (compute-review-status
   [{:author "alice" :state "APPROVED"}
    {:author "bob" :state "CHANGES_REQUESTED"}
    {:author "charlie" :state "APPROVED"}]
   2)
  ; => {:status :changes-requested :approvers [...] ...}

  ;; Parse review decision
  (parse-review-decision "APPROVED")      ; => :approved
  (parse-review-decision "REVIEW_REQUIRED") ; => :pending

  ;; Track comments
  (def tracker (create-comment-tracker))
  (track-comment! tracker {:id 1 :body "First comment"})  ; => true (new)
  (track-comment! tracker {:id 1 :body "First comment"})  ; => false (seen)

  :leave-this-here)
