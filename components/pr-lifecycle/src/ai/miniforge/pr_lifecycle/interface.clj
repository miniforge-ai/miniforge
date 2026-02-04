(ns ai.miniforge.pr-lifecycle.interface
  "Public API for the PR lifecycle component.

   Provides automated PR lifecycle management including:
   - Branch/PR creation
   - CI status monitoring
   - Review monitoring
   - Comment triage (actionable vs non-actionable)
   - Automated fix generation for CI failures and review feedback
   - Merge policy enforcement

   The PR lifecycle controller drives tasks from implementation
   through merge with minimal manual intervention."
  (:require
   [ai.miniforge.pr-lifecycle.controller :as controller]
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [ai.miniforge.pr-lifecycle.review-monitor :as review]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]
   [ai.miniforge.pr-lifecycle.triage :as triage]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.events :as events]))

;------------------------------------------------------------------------------ Layer 0
;; Events

(def event-types
  "Valid PR lifecycle event types.
   :pr/opened :pr/ci-passed :pr/ci-failed :pr/review-approved
   :pr/review-changes-requested :pr/comment-actionable :pr/merged
   :pr/closed :pr/rebase-needed :pr/conflict :pr/fix-pushed"
  events/event-types)

(def create-event-bus
  "Create an event bus for publishing and subscribing to PR events.

   Example:
     (def bus (create-event-bus))
     (subscribe! bus :my-handler (fn [e] (println e)))"
  events/create-event-bus)

(def publish!
  "Publish an event to the event bus."
  events/publish!)

(def subscribe!
  "Subscribe to events on the event bus.

   Example:
     (subscribe! bus :ci-events
                 (fn [e] (handle-ci-event e))
                 (fn [e] (#{:pr/ci-passed :pr/ci-failed} (:event/type e))))"
  events/subscribe!)

(def unsubscribe!
  "Unsubscribe from events."
  events/unsubscribe!)

(def events-for-task
  "Get all events for a specific task."
  events/events-for-task)

(def events-for-pr
  "Get all events for a specific PR."
  events/events-for-pr)

;; Event constructors
(def pr-opened events/pr-opened)
(def ci-passed events/ci-passed)
(def ci-failed events/ci-failed)
(def review-approved events/review-approved)
(def review-changes-requested events/review-changes-requested)
(def comment-actionable events/comment-actionable)
(def merged events/merged)
(def rebase-needed events/rebase-needed)
(def fix-pushed events/fix-pushed)

;------------------------------------------------------------------------------ Layer 1
;; Comment triage

(def classify-comment
  "Classify a comment as actionable or non-actionable.

   Arguments:
   - comment: Comment string or map with :body :author :path :line

   Options:
   - :threshold - Actionable score threshold (default 1)
   - :author-whitelist - Authors whose comments are always actionable

   Returns {:actionable? bool :reason keyword :scores {...}}

   Example:
     (classify-comment \"Please fix the null pointer exception\")
     ; => {:actionable? true :reason :actionable-indicators ...}"
  triage/classify-comment)

(def triage-comments
  "Triage a collection of comments.

   Options:
   - :policy - :actionable-only (default) :all :none
   - :threshold - Actionable score threshold
   - :author-whitelist - Authors whose comments are always actionable

   Returns {:actionable [...] :non-actionable [...] :stats {...}}

   Example:
     (triage-comments [\"LGTM\" \"Please add tests\"])"
  triage/triage-comments)

(def parse-ci-failure
  "Parse CI failure logs into structured actionable items.

   Returns {:test-failures [...] :lint-errors [...] :build-errors [...]
            :actionable-summary string}"
  triage/parse-ci-failure)

(def extract-failing-files
  "Extract file paths mentioned in CI failures."
  triage/extract-failing-files)

(def extract-requested-changes
  "Extract specific requested changes from review comments."
  triage/extract-requested-changes)

(def group-changes-by-file
  "Group requested changes by file for efficient processing."
  triage/group-changes-by-file)

;------------------------------------------------------------------------------ Layer 2
;; CI monitoring

(def create-ci-monitor
  "Create a CI monitor for a PR.

   Options:
   - :poll-interval-ms - Polling interval (default 30000)
   - :timeout-ms - Total timeout (default 3600000 = 1 hour)
   - :event-bus - Event bus for publishing events

   Example:
     (def monitor (create-ci-monitor dag-id run-id task-id 123 \"/path/to/repo\"))"
  ci/create-ci-monitor)

(def poll-ci-status
  "Poll CI status once.
   Returns {:status keyword :checks [...] :event event-or-nil}"
  ci/poll-ci-status)

(def run-ci-monitor
  "Run the CI monitor until checks complete or timeout.

   Options:
   - :on-poll - Callback after each poll
   - :on-complete - Callback when complete

   Returns final status map."
  ci/run-ci-monitor)

(def stop-ci-monitor
  "Stop a running CI monitor."
  ci/stop-ci-monitor)

(def compute-ci-status
  "Compute overall CI status from individual checks.
   Returns {:status keyword :passed [] :failed [] :pending []}"
  ci/compute-ci-status)

;------------------------------------------------------------------------------ Layer 2
;; Review monitoring

(def create-review-monitor
  "Create a review monitor for a PR.

   Options:
   - :poll-interval-ms - Polling interval (default 30000)
   - :required-approvals - Number of required approvals (default 1)
   - :event-bus - Event bus for publishing events
   - :triage-policy - Comment triage policy (default :actionable-only)

   Example:
     (def monitor (create-review-monitor dag-id run-id task-id 123 \"/path/to/repo\"
                                         :required-approvals 2))"
  review/create-review-monitor)

(def poll-review-status
  "Poll review status once.
   Returns {:status keyword :reviews {...} :new-comments [...] :events [...]}"
  review/poll-review-status)

(def run-review-monitor
  "Run the review monitor.

   Options:
   - :on-poll - Callback after each poll
   - :stop-on-status - Set of statuses that stop monitoring (default #{:approved})

   Returns final status map."
  review/run-review-monitor)

(def stop-review-monitor
  "Stop a running review monitor."
  review/stop-review-monitor)

(def compute-review-status
  "Compute overall review status from reviews.
   Returns {:status keyword :approvers [...] :changes-requested-by [...]}"
  review/compute-review-status)

;------------------------------------------------------------------------------ Layer 3
;; Fix loop

(def create-fix-context
  "Build context pack for fix generation.

   Arguments:
   - task: Task definition with acceptance criteria
   - pr-info: PR information
   - failure-info: CI/review failure information

   Options:
   - :current-diff - Current PR diff
   - :dependency-artifacts - Artifacts from completed dependencies
   - :previous-fixes - Previous fix attempts"
  fix/create-fix-context)

(def build-fix-prompt
  "Build a prompt for the fix agent based on failure type."
  fix/build-fix-prompt)

(def generate-fix
  "Generate a fix using the inner loop.
   Returns {:success? bool :artifact patch-artifact :metrics {...}}"
  fix/generate-fix)

(def run-fix-loop
  "Run the complete fix loop for a failure.

   Options:
   - :worktree-path - Path to git worktree (required)
   - :max-attempts - Max fix attempts (default 3)
   - :event-bus - Event bus for publishing events

   Returns {:success? bool :commit-sha string :attempts int :metrics {...}}"
  fix/run-fix-loop)

(def fix-ci-failure
  "Convenience function for fixing CI failures."
  fix/fix-ci-failure)

(def fix-review-feedback
  "Convenience function for fixing review feedback."
  fix/fix-review-feedback)

(def fix-merge-conflict
  "Convenience function for fixing merge conflicts."
  fix/fix-merge-conflict)

;------------------------------------------------------------------------------ Layer 4
;; Merge

(def default-merge-policy
  "Default merge policy configuration."
  merge/default-merge-policy)

(def merge-methods
  "Supported merge methods: :merge :squash :rebase"
  merge/merge-methods)

(def evaluate-merge-readiness
  "Evaluate if a PR is ready to merge according to policy.
   Returns {:ready? bool :checks {...} :blocking [...]}

   Example:
     (evaluate-merge-readiness \"/path/to/repo\" 123 default-merge-policy)"
  merge/evaluate-merge-readiness)

(def merge-pr!
  "Merge a PR using gh CLI.

   Options:
   - :policy - Merge policy map

   Example:
     (merge-pr! \"/path/to/repo\" 123 :policy {:method :squash})"
  merge/merge-pr!)

(def enable-auto-merge!
  "Enable auto-merge for a PR."
  merge/enable-auto-merge!)

(def disable-auto-merge!
  "Disable auto-merge for a PR."
  merge/disable-auto-merge!)

(def rebase-pr!
  "Rebase a PR onto the latest base branch."
  merge/rebase-pr!)

(def attempt-merge
  "Attempt to merge a PR, handling common failure cases.
   Returns result with merge status and events."
  merge/attempt-merge)

;------------------------------------------------------------------------------ Layer 5
;; Controller (full lifecycle orchestration)

(def create-controller
  "Create a PR lifecycle controller for a task.

   Arguments:
   - dag-id: DAG run ID
   - run-id: Run instance ID
   - task-id: Task ID
   - task: Task definition

   Options:
   - :worktree-path - Path to git worktree (required)
   - :event-bus - Event bus for publishing events
   - :logger - Logger instance
   - :generate-fn - Agent generation function
   - :merge-policy - Merge policy map
   - :max-fix-iterations - Max fix iterations (default 5)

   Example:
     (def ctrl (create-controller dag-id run-id task-id task
                                  :worktree-path \"/path/to/repo\"
                                  :generate-fn my-generator))"
  controller/create-controller)

(def create-pr!
  "Create a PR for the task.
   Returns result with PR info."
  controller/create-pr!)

(def start-ci-monitoring!
  "Start CI monitoring for the PR."
  controller/start-ci-monitoring!)

(def start-review-monitoring!
  "Start review monitoring for the PR."
  controller/start-review-monitoring!)

(def handle-ci-failure!
  "Handle a CI failure by running the fix loop."
  controller/handle-ci-failure!)

(def handle-review-feedback!
  "Handle review feedback by running the fix loop."
  controller/handle-review-feedback!)

(def attempt-merge!
  "Attempt to merge the PR."
  controller/attempt-merge!)

(def run-lifecycle!
  "Run the full PR lifecycle for a task.

   This is the main entry point that orchestrates:
   1. PR creation
   2. CI monitoring
   3. Review monitoring
   4. Fix loops for failures
   5. Merge

   Arguments:
   - controller: Controller state atom
   - code-artifact: Initial code artifact

   Options:
   - :skip-pr-creation? - Skip PR creation if PR exists
   - :on-event - Callback for lifecycle events

   Returns final status map.

   Example:
     (run-lifecycle! controller code-artifact
                     :on-event #(println \"Event:\" %))"
  controller/run-lifecycle!)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Quick example: Full PR lifecycle

  ;; 1. Create event bus
  (def bus (create-event-bus))

  ;; 2. Create controller
  (def ctrl (create-controller
             (random-uuid) (random-uuid) (random-uuid)
             {:task/id (random-uuid)
              :task/title "Add user login"
              :task/acceptance-criteria ["Login endpoint works"
                                         "Session stored correctly"
                                         "Tests pass"]}
             :worktree-path "/path/to/repo"
             :event-bus bus
             :generate-fn (fn [_task _ctx]
                            {:artifact {:code/files [{:path "src/login.clj"
                                                      :content "(defn login [])"}]}
                             :tokens 100})))

  ;; 3. Run lifecycle
  ;; (run-lifecycle! ctrl {:code/files [...]})

  ;; Triage example
  (triage-comments
   ["LGTM!"
    "Please add error handling for nil input"
    "Nice work overall"
    "This needs a security review - possible SQL injection"])
  ; => {:actionable [{...} {...}] :non-actionable [{...} {...}] ...}

  ;; CI status computation
  (compute-ci-status
   [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}
    {:name "lint" :state "COMPLETED" :conclusion "FAILURE"}])
  ; => {:status :failure :passed [...] :failed [...] ...}

  :leave-this-here)
