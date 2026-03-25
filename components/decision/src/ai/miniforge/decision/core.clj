(ns ai.miniforge.decision.core
  "Pure constructors for canonical checkpoints and episodes.
   Layer 0: Small helper mappings
   Layer 1: Checkpoint constructors
   Layer 2: Episode constructors"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Helper mappings

(def ^:private decision-type->class
  {:approval :approval
   :choice :implementation-pattern-choice
   :input :request-for-input
   :confirmation :confirmation})

(def ^:private priority->risk-tier
  {:critical :critical
   :high :high
   :medium :medium
   :low :low})

(defn- option->alternative
  [idx option]
  {:id idx
   :summary (str option)})

(defn- summarize-loop-errors
  [errors]
  (->> errors
       (keep #(or (get-in % [:anomaly :anomaly/message])
                  (:message %)))
       (remove str/blank?)
       (take 2)
       (str/join "; ")))

;------------------------------------------------------------------------------ Layer 1
;; Checkpoint constructors

(defn create-checkpoint
  "Create a canonical normalized decision checkpoint."
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

(defn resolve-checkpoint
  "Resolve a checkpoint with a structured supervision response."
  [checkpoint response]
  (assoc checkpoint
         :checkpoint/status :resolved
         :checkpoint/resolved-at (java.util.Date.)
         :response response))

(defn create-control-plane-checkpoint
  "Normalize a control-plane decision request into the canonical checkpoint shape."
  [agent-id summary opts]
  (let [decision-type (get opts :type :choice)
        priority (get opts :priority :medium)
        options (:options opts)
        proposal (cond-> {:action-type :request-human-decision
                          :decision-class (get decision-type->class decision-type :implementation-pattern-choice)
                          :summary summary}
                   (seq options) (assoc :alternatives
                                        (mapv option->alternative (range) options)))
        uncertainty (cond-> {:class :agent-request
                             :reason "External or delegated agent requested human judgment."}
                      (some? (:agent-confidence opts))
                      (assoc :agent-confidence (:agent-confidence opts)))]
    (create-checkpoint
     {:source {:kind :control-plane-agent
               :agent-id agent-id}
      :task {:kind :control-plane-decision
             :goal summary}
      :proposal proposal
      :uncertainty uncertainty
      :risk {:tier (get priority->risk-tier priority :medium)}
      :context (cond-> {}
                 (:context opts) (assoc :context/text (:context opts))
                 (:deadline opts) (assoc :context/deadline (:deadline opts))
                 (:tags opts) (assoc :context/tags (set (:tags opts))))})))

(defn create-loop-escalation-checkpoint
  "Normalize an inner-loop escalation into the canonical checkpoint shape."
  [loop-state opts]
  (let [task (:loop/task loop-state)
        artifact (:loop/artifact loop-state)
        errors (:loop/errors loop-state)
        content (some-> artifact :artifact/content str)
        summary (or (:summary opts)
                    (str "Loop escalation after "
                         (:loop/iteration loop-state)
                         " attempt(s) for task "
                         (name (or (:task/type task) :unknown))))
        diff-summary (when content
                       (subs content 0 (min 160 (count content))))
        task-info (cond-> {:kind :loop-escalation
                           :goal summary}
                    (:task/id task) (assoc :task-id (:task/id task)))
        proposal (cond-> {:action-type :provide-guidance
                          :decision-class :repair-escalation
                          :summary summary}
                   (:artifact/path artifact) (assoc :files [(:artifact/path artifact)])
                   diff-summary (assoc :diff-summary diff-summary))]
    (create-checkpoint
     {:source {:kind :loop-escalation
               :loop-id (:loop/id loop-state)}
      :task task-info
      :proposal proposal
      :uncertainty {:class :validation-failure
                    :reason (or (summarize-loop-errors errors)
                                "Loop exhausted repair budget without convergence.")}
      :risk {:tier (get opts :risk-tier :medium)}
      :context {:loop/iteration (:loop/iteration loop-state)
                :loop/state (:loop/state loop-state)
                :loop/termination (:loop/termination loop-state)
                :task/type (:task/type task)
                :error-count (count errors)}})))

;------------------------------------------------------------------------------ Layer 2
;; Episode constructors

(defn create-episode
  "Create an episode shell for a checkpoint."
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

(defn update-episode
  "Update an existing episode from the latest checkpoint state."
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
