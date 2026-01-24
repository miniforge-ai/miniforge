(ns ai.miniforge.agent.protocols.impl.specialized
  "Implementation functions for specialized agents.

   These functions implement the logic for the FunctionalAgent record."
  (:require
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Helper functions for cycle-agent

(defn handle-first-iteration
  "Handle the first iteration: invoke the agent and validate output.
   Returns either the successful result or continues to repair loop."
  [agent context input max-iterations]
  (let [result ((:invoke-fn agent) context input)]
    (if (= :error (:status result))
      result
      (let [validation ((:validate-fn agent) (:output result))]
        (if (:valid? validation)
          result
          (if (>= 0 max-iterations)
            {:status :error
             :output (:output result)
             :errors (:errors validation)
             :message "Max repair iterations reached"}
            {:continue-repair true
             :iteration 1
             :output (:output result)}))))))

(defn handle-repair-iteration
  "Handle a repair iteration: validate output and attempt repair if needed.
   Returns either success, error, or continuation signal."
  [agent current-output iteration max-iterations context]
  (let [validation ((:validate-fn agent) current-output)]
    (if (:valid? validation)
      {:status :success :output current-output}
      (if (>= iteration max-iterations)
        {:status :error
         :output current-output
         :errors (:errors validation)
         :message "Max repair iterations reached"}
        (let [repair-result ((:repair-fn agent) current-output (:errors validation) context)]
          (if (= :error (:status repair-result))
            repair-result
            {:continue-repair true
             :iteration (inc iteration)
             :output (:output repair-result)}))))))

;------------------------------------------------------------------------------ Layer 1
;; Protocol implementations for FunctionalAgent

(defn invoke-impl
  "Implementation of invoke protocol method for FunctionalAgent."
  [agent task context]
  (let [role (:role agent)
        logger (:logger agent)
        invoke-fn (:invoke-fn agent)
        start-time (System/currentTimeMillis)]
    (try
      (log/debug logger :agent :agent/invoke-started
                 {:data {:role role :task-type (:task/type task)}})
      (let [result (invoke-fn context task)]
        (log/info logger :agent :agent/invoke-completed
                  {:data {:role role
                          :duration-ms (- (System/currentTimeMillis) start-time)
                          :status (:status result)}})
        result)
      (catch Exception e
        (log/error logger :agent :agent/invoke-failed
                   {:message (.getMessage e)
                    :data {:role role
                           :duration-ms (- (System/currentTimeMillis) start-time)}})
        {:status :error
         :error (.getMessage e)
         :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}}))))

(defn validate-impl
  "Implementation of validate protocol method for FunctionalAgent."
  [agent output _context]
  ((:validate-fn agent) output))

(defn repair-impl
  "Implementation of repair protocol method for FunctionalAgent."
  [agent output errors context]
  (let [role (:role agent)
        logger (:logger agent)
        repair-fn (:repair-fn agent)]
    (log/debug logger :agent :agent/repair-started
               {:data {:role role :error-count (count (if (map? errors) (vals errors) errors))}})
    (let [result (repair-fn output errors context)]
      (log/info logger :agent :agent/repair-completed
                {:data {:role role :status (:status result)}})
      result)))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration functions

(defn cycle-agent-impl
  "Execute a full invoke-validate-repair cycle on a specialized agent.
   Returns the final result after up to max-iterations of repair attempts."
  [agent context input max-iterations]
  (loop [iteration 0
         current-output nil]
    (let [result (if (zero? iteration)
                   (handle-first-iteration agent context input max-iterations)
                   (handle-repair-iteration agent current-output iteration max-iterations context))]
      (if (:continue-repair result)
        (recur (:iteration result) (:output result))
        result))))
