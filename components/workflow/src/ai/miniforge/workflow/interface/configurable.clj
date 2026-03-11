(ns ai.miniforge.workflow.interface.configurable
  "Configurable workflow loading, execution, persistence, and triggers."
  (:require
   [ai.miniforge.workflow.persistence :as persist]
   [ai.miniforge.workflow.replay :as replay]))

;------------------------------------------------------------------------------ Layer 0
;; Configurable workflows and persistence

(defn load-workflow
  ([workflow-id version]
   (load-workflow workflow-id version {}))
  ([workflow-id version opts]
   ((requiring-resolve 'ai.miniforge.workflow.loader/load-workflow)
    workflow-id version opts)))

(defn execute-workflow
  ([workflow input]
   (execute-workflow workflow input {}))
  ([workflow input opts]
   ((requiring-resolve 'ai.miniforge.workflow.configurable/run-configurable-workflow)
    workflow input opts)))

(def get-active-workflow persist/get-active-workflow-id)
(def set-active-workflow persist/set-active-workflow)
(def append-event persist/append-event)
(def load-event-log persist/load-event-log)

(defn replay-workflow
  [events & {:keys [workflow-id until] :as _opts}]
  (replay/replay-workflow events :workflow-id workflow-id :until until))

(defn verify-determinism
  [events expected-state & {:keys [workflow-id until] :as _opts}]
  (replay/verify-determinism events expected-state :workflow-id workflow-id :until until))

(defn save-workflow
  ([workflow]
   (save-workflow workflow {}))
  ([workflow opts]
   ((requiring-resolve 'ai.miniforge.workflow.comparison/save-workflow)
    workflow opts)))

(defn list-workflows
  []
  ((requiring-resolve 'ai.miniforge.workflow.loader/list-available-workflows)))

(defn compare-workflows
  [execution-states]
  ((requiring-resolve 'ai.miniforge.workflow.comparison/compare-workflows)
   execution-states))

(defn create-merge-trigger
  [event-stream trigger-config opts]
  ((requiring-resolve 'ai.miniforge.workflow.trigger/create-merge-trigger)
   event-stream trigger-config opts))

(defn stop-trigger!
  [trigger]
  ((requiring-resolve 'ai.miniforge.workflow.trigger/stop-trigger!) trigger))
