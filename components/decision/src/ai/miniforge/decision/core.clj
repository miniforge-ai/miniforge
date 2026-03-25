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

(def ^:private decision-type->response-type
  {:approval :approve
   :choice :choose-option
   :input :approve-with-constraints
   :confirmation :approve})

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

(defn- control-plane-proposal
  [summary options decision-type]
  (cond-> {:action-type :request-human-decision
           :decision-class (get decision-type->class decision-type
                                :implementation-pattern-choice)
           :summary summary}
    (seq options) (assoc :alternatives
                         (mapv option->alternative (range) options))))

(defn- control-plane-uncertainty
  [agent-confidence]
  (cond-> {:class :agent-request
           :reason "External or delegated agent requested human judgment."}
    (some? agent-confidence) (assoc :agent-confidence agent-confidence)))

(defn- control-plane-context
  [{:keys [context deadline tags]}]
  (cond-> {}
    context (assoc :context/text context)
    deadline (assoc :context/deadline deadline)
    (seq tags) (assoc :context/tags (set tags))))

(defn- control-plane-checkpoint-input
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

(defn- loop-escalation-summary
  [loop-state summary]
  (or summary
      (str "Loop escalation after "
           (:loop/iteration loop-state)
           " attempt(s) for task "
           (name (or (get-in loop-state [:loop/task :task/type]) :unknown)))))

(defn- loop-escalation-task
  [task summary]
  (cond-> {:kind :loop-escalation
           :goal summary}
    (:task/id task) (assoc :task-id (:task/id task))))

(defn- loop-escalation-proposal
  [artifact summary]
  (let [content (some-> artifact :artifact/content str)
        diff-summary (when content
                       (subs content 0 (min 160 (count content))))]
    (cond-> {:action-type :provide-guidance
             :decision-class :repair-escalation
             :summary summary}
      (:artifact/path artifact) (assoc :files [(:artifact/path artifact)])
      diff-summary (assoc :diff-summary diff-summary))))

(defn- loop-escalation-uncertainty
  [errors]
  {:class :validation-failure
   :reason (or (summarize-loop-errors errors)
               "Loop exhausted repair budget without convergence.")})

(defn- loop-escalation-context
  [{:loop/keys [iteration state termination task errors]}]
  {:loop/iteration iteration
   :loop/state state
   :loop/termination termination
   :task/type (:task/type task)
   :error-count (count errors)})

(defn- loop-escalation-checkpoint-input
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

(defn decision-response
  "Create a structured response from a decision type and outcome."
  [decision-type resolution & [rationale]]
  (cond-> {:type (get decision-type->response-type decision-type :approve)
           :value resolution
           :authority-role :human}
    rationale (assoc :rationale rationale)))

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
  (-> (control-plane-checkpoint-input agent-id summary opts)
      create-checkpoint))

(defn create-loop-escalation-checkpoint
  "Normalize an inner-loop escalation into the canonical checkpoint shape."
  [loop-state opts]
  (-> (loop-escalation-checkpoint-input loop-state opts)
      create-checkpoint))

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
