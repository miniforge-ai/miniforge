(ns ai.miniforge.workflow.interface.configurable
  "Configurable workflow loading, execution, persistence, triggers, and publication."
  (:require
   [ai.miniforge.workflow.comparison :as comparison]
   [ai.miniforge.workflow.configurable :as configurable]
   [ai.miniforge.workflow.loader :as loader]
   [ai.miniforge.workflow.persistence :as persist]
   [ai.miniforge.workflow.publish :as publish]
   [ai.miniforge.workflow.replay :as replay]
   [ai.miniforge.workflow.trigger :as trigger]))

;------------------------------------------------------------------------------ Layer 0
;; Configurable workflows and persistence

(defn load-workflow
  ([workflow-id version]
   (load-workflow workflow-id version {}))
  ([workflow-id version opts]
   (loader/load-workflow workflow-id version opts)))

(defn execute-workflow
  ([workflow input]
   (execute-workflow workflow input {}))
  ([workflow input opts]
   (configurable/run-configurable-workflow workflow input opts)))

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
   (comparison/save-workflow workflow opts)))

(defn list-workflows
  []
  (loader/list-available-workflows))

(defn compare-workflows
  [execution-states]
  (comparison/compare-workflows execution-states))

(defn create-directory-publisher
  ([output-dir]
   (create-directory-publisher output-dir {}))
  ([output-dir opts]
   (publish/create-directory-publisher output-dir opts)))

(defn publish-output!
  ([publisher publication]
   (publish-output! publisher publication nil))
  ([publisher publication logger]
   (publish/publish! publisher publication logger)))

(defn create-event-trigger
  [event-stream trigger-config opts]
  (trigger/create-event-trigger event-stream trigger-config opts))

(defn create-merge-trigger
  [event-stream trigger-config opts]
  (create-event-trigger event-stream trigger-config opts))

(defn stop-trigger!
  [trigger-handle]
  (trigger/stop-trigger! trigger-handle))
