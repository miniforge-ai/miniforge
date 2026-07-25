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
(ns ai.miniforge.decision.core
  "Pure constructors for canonical checkpoints and episodes.
   Layer 0: Small helper mappings
   Layer 1: Checkpoint constructors
   Layer 2: Episode constructors"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Helper mappings
(def ^{:stratum 0} ^:private decision-type->class
  {:approval :approval
   :choice :implementation-pattern-choice
   :input :request-for-input
   :confirmation :confirmation})

(def ^{:stratum 0} ^:private priority->risk-tier
  {:critical :critical
   :high :high
   :medium :medium
   :low :low})

(def ^{:stratum 0} ^:private decision-type->response-type
  {:approval :approve
   :choice :choose-option
   :input :approve-with-constraints
   :confirmation :approve})

(defn- ^{:stratum 0} option->alternative
  [idx option]
  {:id idx
   :summary (str option)})

(defn- ^{:stratum 0} summarize-loop-errors
  "Return a joined summary of the first two non-blank error messages, or nil
   when the error list yields nothing usable. Returning nil (rather than the
   empty string) lets callers fall back via `or` and keeps :uncertainty/:reason
   schema-valid (it requires :string {:min 1})."
  [errors]
  (let [joined (->> errors
                    (keep #(or (get-in % [:anomaly :anomaly/message])
                               (:message %)))
                    (remove str/blank?)
                    (take 2)
                    (str/join "; "))]
    (when-not (str/blank? joined)
      joined)))

(defn- ^{:stratum 0} control-plane-uncertainty
  [agent-confidence]
  (cond-> {:class :agent-request
           :reason "External or delegated agent requested human judgment."}
    (some? agent-confidence) (assoc :agent-confidence agent-confidence)))

(defn- ^{:stratum 0} control-plane-context
  [{:keys [context deadline tags]}]
  (cond-> {}
    context (assoc :context/text context)
    deadline (assoc :context/deadline deadline)
    (seq tags) (assoc :context/tags (set tags))))

(defn- ^{:stratum 0} loop-escalation-summary
  [loop-state summary]
  (or summary
      (str "Loop escalation after "
           (:loop/iteration loop-state)
           " attempt(s) for task "
           (name (or (get-in loop-state [:loop/task :task/type]) :unknown)))))

(defn- ^{:stratum 0} loop-escalation-task
  [task summary]
  (cond-> {:kind :loop-escalation
           :goal summary}
    (:task/id task) (assoc :task-id (:task/id task))))

(defn- ^{:stratum 0} loop-escalation-proposal
  [artifact summary]
  (let [content (some-> artifact :artifact/content str)
        diff-summary (when content
                       (subs content 0 (min 160 (count content))))]
    (cond-> {:action-type :provide-guidance
             :decision-class :repair-escalation
             :summary summary}
      (:artifact/path artifact) (assoc :files [(:artifact/path artifact)])
      diff-summary (assoc :diff-summary diff-summary))))

(defn- ^{:stratum 0} loop-escalation-context
  [{:loop/keys [iteration state termination task errors]}]
  {:loop/iteration iteration
   :loop/state state
   :loop/termination termination
   :task/type (:task/type task)
   :error-count (count errors)})

;; Checkpoint constructors
(defn ^{:stratum 0} create-checkpoint
  [{:keys [checkpoint-id status created-at resolved-at requested-authority
           source task proposal uncertainty risk context response]}]
  (cond-> {:checkpoint/id (or checkpoint-id (random-uuid))
           :checkpoint/status (or status :pending)
           :checkpoint/created-at (or created-at (java.util.Date.))
           :checkpoint/requested-authority (or requested-authority :human)
           :source source
           :proposal proposal}
    task (assoc :task task)
    uncertainty (assoc :uncertainty uncertainty)
    risk (assoc :risk risk)
    context (assoc :context context)
    response (assoc :response response)
    resolved-at (assoc :checkpoint/resolved-at resolved-at)))

(defn ^{:stratum 0} resolve-checkpoint
  [checkpoint response]
  (assoc checkpoint
         :checkpoint/status :resolved
         :checkpoint/resolved-at (java.util.Date.)
         :response response))

;; Episode constructors
(defn ^{:stratum 0} create-episode
  [checkpoint]
  (let [now (java.util.Date.)]
    (cond-> {:episode/id (random-uuid)
             :episode/status (if (= :resolved (:checkpoint/status checkpoint))
                               :resolved
                               :pending)
             :episode/created-at now
             :episode/updated-at now
             :checkpoint checkpoint}
      (:response checkpoint) (assoc :supervision (:response checkpoint)))))

(defn ^{:stratum 0} update-episode
  [episode checkpoint & [opts]]
  (cond-> (assoc episode
                 :episode/status (cond
                                   (:downstream-outcome opts) :completed
                                   (= :resolved (:checkpoint/status checkpoint)) :resolved
                                   :else :pending)
                 :episode/updated-at (java.util.Date.)
                 :checkpoint checkpoint)
    (:response checkpoint) (assoc :supervision (:response checkpoint))
    (:execution-result opts) (assoc :execution-result (:execution-result opts))
    (:downstream-outcome opts) (assoc :downstream-outcome (:downstream-outcome opts))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} control-plane-proposal
  [summary options decision-type]
  (cond-> {:action-type :request-human-decision
           :decision-class (get decision-type->class decision-type
                                :implementation-pattern-choice)
           :summary summary}
    (seq options) (assoc :alternatives
                         (mapv option->alternative (range) options))))

(defn- ^{:stratum 1} loop-escalation-uncertainty
  [errors]
  {:class :validation-failure
   :reason (or (summarize-loop-errors errors)
               "Loop exhausted repair budget without convergence.")})

(defn ^{:stratum 1} decision-response
  [decision-type resolution & [rationale]]
  (cond-> {:type (get decision-type->response-type decision-type :approve)
           :value resolution
           :authority-role :human}
    rationale (assoc :rationale rationale)))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} control-plane-checkpoint-input
  [agent-id summary {:keys [type priority options agent-confidence]
                     :as opts}]
  {:source {:kind :control-plane-agent
            :agent-id agent-id}
   :task {:kind :control-plane-decision
          :goal summary}
   :proposal (control-plane-proposal summary options (or type :choice))
   :uncertainty (control-plane-uncertainty agent-confidence)
   :risk {:tier (get priority->risk-tier (or priority :medium) :medium)}
   :context (control-plane-context opts)})

(defn- ^{:stratum 2} loop-escalation-checkpoint-input
  [loop-state {:keys [summary risk-tier]}]
  (let [summary (loop-escalation-summary loop-state summary)
        task (:loop/task loop-state)
        artifact (:loop/artifact loop-state)
        errors (:loop/errors loop-state)]
    {:source {:kind :loop-escalation
              :loop-id (:loop/id loop-state)}
     :task (loop-escalation-task task summary)
     :proposal (loop-escalation-proposal artifact summary)
     :uncertainty (loop-escalation-uncertainty errors)
     :risk {:tier (or risk-tier :medium)}
     :context (loop-escalation-context loop-state)}))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} create-control-plane-checkpoint
  [agent-id summary opts]
  (-> (control-plane-checkpoint-input agent-id summary opts)
      create-checkpoint))

(defn ^{:stratum 3} create-loop-escalation-checkpoint
  [loop-state opts]
  (-> (loop-escalation-checkpoint-input loop-state opts)
      create-checkpoint))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def cp-checkpoint
    (create-control-plane-checkpoint
     (random-uuid)
     "Should I merge PR #42?"
     {:type :approval
      :priority :high
      :options ["yes" "no"]}))

  (def resolved
    (resolve-checkpoint cp-checkpoint
                        {:type :approve
                         :value "yes"
                         :authority-role :human}))

  (def episode
    (create-episode cp-checkpoint))

  (update-episode episode resolved)

  :leave-this-here)
