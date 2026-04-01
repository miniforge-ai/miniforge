(ns ai.miniforge.pr-lifecycle.monitor-events
  "PR monitor loop event types and constructors.

   These events track the autonomous PR monitor loop activity:
   polling, classification, fix cycles, replies, and budget enforcement.
   Complement the base :pr/* events in events.clj with finer-grained
   :pr-monitor/* events for TUI visibility."
  (:require
   [ai.miniforge.pr-lifecycle.events :as events]))

;------------------------------------------------------------------------------ Layer 0
;; Event types

(def monitor-event-types
  "Valid PR monitor loop event types."
  #{:pr-monitor/poll-completed       ; Poll cycle completed
    :pr-monitor/comment-received     ; New comment detected
    :pr-monitor/comment-classified   ; Comment classified into category
    :pr-monitor/fix-started          ; Fix cycle initiated for change-request
    :pr-monitor/fix-pushed           ; Fix committed and pushed
    :pr-monitor/reply-posted         ; Reply comment posted to PR
    :pr-monitor/question-answered    ; Question answered via LLM reply
    :pr-monitor/budget-warning       ; Budget approaching limit
    :pr-monitor/budget-exhausted     ; Budget exceeded — hard stop
    :pr-monitor/escalated            ; Escalated to human intervention
    :pr-monitor/cycle-completed      ; Full monitor cycle completed
    :pr-monitor/loop-started         ; Monitor loop started for a PR
    :pr-monitor/loop-stopped})       ; Monitor loop stopped

;------------------------------------------------------------------------------ Layer 1
;; Event constructors

(defn poll-completed
  "Create a poll-completed event."
  [pr-number data]
  (events/create-event :pr-monitor/poll-completed
                       (merge {:pr/id pr-number} data)))

(defn comment-received
  "Create a comment-received event."
  [pr-number comment-data]
  (events/create-event :pr-monitor/comment-received
                       {:pr/id pr-number
                        :comment comment-data}))

(defn comment-classified
  "Create a comment-classified event."
  [pr-number comment-data classification]
  (events/create-event :pr-monitor/comment-classified
                       {:pr/id pr-number
                        :comment comment-data
                        :classification classification}))

(defn fix-started
  "Create a fix-started event."
  [pr-number comment-id attempt]
  (events/create-event :pr-monitor/fix-started
                       {:pr/id pr-number
                        :comment/id comment-id
                        :fix/attempt attempt}))

(defn fix-pushed
  "Create a fix-pushed event."
  [pr-number comment-id commit-sha]
  (events/create-event :pr-monitor/fix-pushed
                       {:pr/id pr-number
                        :comment/id comment-id
                        :pr/sha commit-sha}))

(defn reply-posted
  "Create a reply-posted event."
  [pr-number comment-id reply-type]
  (events/create-event :pr-monitor/reply-posted
                       {:pr/id pr-number
                        :comment/id comment-id
                        :reply/type reply-type}))

(defn question-answered
  "Create a question-answered event."
  [pr-number comment-id]
  (events/create-event :pr-monitor/question-answered
                       {:pr/id pr-number
                        :comment/id comment-id}))

(defn budget-warning
  "Create a budget-warning event."
  [pr-number budget-remaining budget-total]
  (events/create-event :pr-monitor/budget-warning
                       {:pr/id pr-number
                        :budget/remaining budget-remaining
                        :budget/total budget-total}))

(defn budget-exhausted
  "Create a budget-exhausted event. This is a hard stop."
  [pr-number budget-data]
  (events/create-event :pr-monitor/budget-exhausted
                       (merge {:pr/id pr-number} budget-data)))

(defn escalated
  "Create an escalated-to-human event."
  [pr-number reason data]
  (events/create-event :pr-monitor/escalated
                       (merge {:pr/id pr-number
                               :escalation/reason reason}
                              data)))

(defn cycle-completed
  "Create a cycle-completed event."
  [pr-number cycle-data]
  (events/create-event :pr-monitor/cycle-completed
                       (merge {:pr/id pr-number} cycle-data)))

(defn loop-started
  "Create a loop-started event."
  [pr-number config]
  (events/create-event :pr-monitor/loop-started
                       {:pr/id pr-number
                        :config (select-keys config [:poll-interval-ms
                                                     :max-fix-attempts-per-comment
                                                     :max-total-fix-attempts-per-pr
                                                     :abandon-after-hours])}))

(defn loop-stopped
  "Create a loop-stopped event."
  [pr-number reason]
  (events/create-event :pr-monitor/loop-stopped
                       {:pr/id pr-number
                        :stop/reason reason}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create events
  (poll-completed 123 {:new-comment-count 3})
  ;; => {:event/id uuid :event/type :pr-monitor/poll-completed :pr/id 123 ...}

  (comment-received 123 {:comment/body "Please fix this" :comment/author "alice"})

  (budget-exhausted 123 {:exhausted-by :pr-limit :total-attempts 10})

  (loop-started 123 {:poll-interval-ms 60000
                     :max-fix-attempts-per-comment 3
                     :max-total-fix-attempts-per-pr 10
                     :abandon-after-hours 72})

  :leave-this-here)
