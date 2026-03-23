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

(ns ai.miniforge.control-plane.decision-queue
  "Priority-ranked decision queue for the control plane.

   Decisions are requests from agents that require human attention.
   They are ranked by priority (critical > high > medium > low),
   then by age (oldest first), with agents in :blocked state
   receiving a boost.

   Built on the approval.clj pattern: atom-backed store with CRUD.

   Layer 0: Decision creation
   Layer 1: Decision resolution and status
   Layer 2: Queue manager (atom-backed store)
   Layer 3: Priority sorting and queries")

;------------------------------------------------------------------------------ Layer 0
;; Decision creation

(def ^:const priority-order
  "Priority ranking — lower index = higher priority."
  [:critical :high :medium :low])

(def ^:private priority-rank
  "Map of priority keyword to numeric rank."
  (zipmap priority-order (range)))

(defn create-decision
  "Create a new decision request.

   Arguments:
   - agent-id  - UUID of the agent needing a decision
   - summary   - Human-readable summary of what's needed
   - opts      - Optional map:
     - :type     - :approval :choice :input :confirmation (default :choice)
     - :priority - :critical :high :medium :low (default :medium)
     - :context  - Rich context string for the human
     - :options  - Vector of structured choice strings
     - :deadline - java.util.Date when this becomes stale
     - :tags     - Set of keyword tags for filtering

   Returns: Decision record map.

   Example:
     (create-decision agent-id \"Should I merge PR #42?\"
                      {:type :approval
                       :priority :high
                       :options [\"yes\" \"no\" \"defer\"]})"
  [agent-id summary & [opts]]
  (let [now (java.util.Date.)]
    (cond-> {:decision/id (random-uuid)
             :decision/agent-id agent-id
             :decision/type (get opts :type :choice)
             :decision/priority (get opts :priority :medium)
             :decision/status :pending
             :decision/summary summary
             :decision/created-at now
             :decision/resolution nil
             :decision/comment nil
             :decision/resolved-at nil}
      (:context opts)  (assoc :decision/context (:context opts))
      (:options opts)  (assoc :decision/options (vec (:options opts)))
      (:deadline opts) (assoc :decision/deadline (:deadline opts))
      (:tags opts)     (assoc :decision/tags (set (:tags opts))))))

;------------------------------------------------------------------------------ Layer 1
;; Decision resolution and status

(defn pending?
  "Check if a decision is still pending."
  [decision]
  (= :pending (:decision/status decision)))

(defn resolved?
  "Check if a decision has been resolved."
  [decision]
  (= :resolved (:decision/status decision)))

(defn expired?
  "Check if a decision has expired past its deadline."
  [decision]
  (or (= :expired (:decision/status decision))
      (when-let [deadline (:decision/deadline decision)]
        (and (pending? decision)
             (.after (java.util.Date.) deadline)))))

(defn resolve-decision
  "Resolve a decision with the human's choice.

   Arguments:
   - decision   - Decision record map
   - resolution - The human's choice (string or keyword)
   - comment    - Optional comment string

   Returns: Updated decision record with :resolved status."
  [decision resolution & [comment]]
  (assoc decision
         :decision/status :resolved
         :decision/resolution resolution
         :decision/comment comment
         :decision/resolved-at (java.util.Date.)))

(defn expire-decision
  "Mark a decision as expired."
  [decision]
  (assoc decision :decision/status :expired))

;------------------------------------------------------------------------------ Layer 2
;; Queue manager (atom-backed store)

(defn create-decision-manager
  "Create a new decision manager.

   Returns: Atom containing {:decisions {}}.

   Example:
     (def mgr (create-decision-manager))"
  []
  (atom {:decisions {}}))

(defn submit-decision!
  "Submit a new decision to the queue.

   Arguments:
   - manager  - Decision manager atom
   - decision - Decision record (from create-decision)

   Returns: The submitted decision."
  [manager decision]
  (swap! manager assoc-in [:decisions (:decision/id decision)] decision)
  decision)

(defn resolve-decision!
  "Resolve a pending decision in the queue.

   Arguments:
   - manager     - Decision manager atom
   - decision-id - UUID of the decision
   - resolution  - The human's choice
   - comment     - Optional comment string

   Returns: Updated decision, or nil if not found or not pending."
  [manager decision-id resolution & [comment]]
  (let [result (atom nil)]
    (swap! manager
           (fn [state]
             (if-let [decision (get-in state [:decisions decision-id])]
               (if (pending? decision)
                 (let [resolved (resolve-decision decision resolution comment)]
                   (reset! result resolved)
                   (assoc-in state [:decisions decision-id] resolved))
                 (do (reset! result nil) state))
               (do (reset! result nil) state))))
    @result))

(defn cancel-decision!
  "Cancel a pending decision.

   Returns: Updated decision, or nil if not found."
  [manager decision-id]
  (let [result (atom nil)]
    (swap! manager
           (fn [state]
             (if-let [decision (get-in state [:decisions decision-id])]
               (let [cancelled (assoc decision :decision/status :cancelled)]
                 (reset! result cancelled)
                 (assoc-in state [:decisions decision-id] cancelled))
               (do (reset! result nil) state))))
    @result))

(defn get-decision
  "Get a decision by ID."
  [manager decision-id]
  (get-in @manager [:decisions decision-id]))

(defn expire-stale-decisions!
  "Expire all decisions past their deadline.

   Returns: Seq of expired decision IDs."
  [manager]
  (let [now (java.util.Date.)
        expired-ids (atom [])]
    (swap! manager
           (fn [state]
             (reduce-kv
              (fn [s id decision]
                (if (and (pending? decision)
                         (:decision/deadline decision)
                         (.after now (:decision/deadline decision)))
                  (do (swap! expired-ids conj id)
                      (assoc-in s [:decisions id] (expire-decision decision)))
                  s))
              state
              (:decisions state))))
    @expired-ids))

;------------------------------------------------------------------------------ Layer 3
;; Priority sorting and queries

(defn- decision-sort-key
  "Compute sort key for priority ordering.
   Lower values = higher priority."
  [decision blocked-agent-ids]
  (let [base-priority (get priority-rank (:decision/priority decision) 99)
        ;; Agents in :blocked state get a -1 priority boost
        blocked-boost (if (contains? blocked-agent-ids (:decision/agent-id decision))
                        -1 0)
        effective-priority (+ base-priority blocked-boost)]
    [effective-priority (.getTime (:decision/created-at decision))]))

(defn pending-decisions
  "Get all pending decisions, sorted by priority.

   Arguments:
   - manager           - Decision manager atom
   - blocked-agent-ids - Set of agent UUIDs in :blocked state (for priority boost)

   Returns: Seq of pending decision records, highest priority first."
  [manager & [blocked-agent-ids]]
  (let [blocked (or blocked-agent-ids #{})]
    (->> (vals (:decisions @manager))
         (filter pending?)
         (sort-by #(decision-sort-key % blocked)))))

(defn decisions-for-agent
  "Get all decisions for a specific agent.

   Options:
   - :status - Filter by decision status (:pending, :resolved, :expired)

   Returns: Seq of decision records."
  [manager agent-id & [opts]]
  (let [decisions (vals (:decisions @manager))]
    (cond->> decisions
      true           (filter #(= agent-id (:decision/agent-id %)))
      (:status opts) (filter #(= (:status opts) (:decision/status %))))))

(defn count-pending
  "Count the number of pending decisions."
  [manager]
  (count (filter pending? (vals (:decisions @manager)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def mgr (create-decision-manager))
  (def agent-id (random-uuid))
  (def d (create-decision agent-id "Should I merge PR #42?"
                          {:type :approval
                           :priority :high
                           :options ["yes" "no" "defer"]}))
  (submit-decision! mgr d)
  (pending-decisions mgr)
  (resolve-decision! mgr (:decision/id d) "yes" "Ship it")
  (get-decision mgr (:decision/id d))
  :end)
