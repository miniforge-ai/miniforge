(ns ai.miniforge.workflow.interface.runtime
  "Workflow creation, lifecycle, and phase execution API."
  (:require
   [ai.miniforge.workflow.core :as core]
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.interface.protocols.workflow :as workflow-proto]))

;------------------------------------------------------------------------------ Layer 0
;; Workflow lifecycle

(def create-workflow core/create-workflow)
(def add-observer core/add-observer)
(def remove-observer core/remove-observer)
(def create-phase-executor core/create-phase-executor)
(def run-workflow core/run-workflow)

(defn start
  [workflow spec context]
  (workflow-proto/start workflow spec context))

(defn get-state
  [workflow workflow-id]
  (workflow-proto/get-state workflow workflow-id))

(defn advance
  [workflow workflow-id phase-result]
  (workflow-proto/advance workflow workflow-id phase-result))

(defn rollback
  [workflow workflow-id target-phase reason]
  (workflow-proto/rollback workflow workflow-id target-phase reason))

(defn complete
  [workflow workflow-id]
  (workflow-proto/complete workflow workflow-id))

(defn fail
  [workflow workflow-id error]
  (workflow-proto/fail workflow workflow-id error))

(defn execute-phase
  [executor workflow-state context]
  (executor-proto/execute-phase executor workflow-state context))

(defn can-execute?
  [executor phase]
  (executor-proto/can-execute? executor phase))

(defn get-phase-requirements
  [executor phase]
  (executor-proto/get-phase-requirements executor phase))
