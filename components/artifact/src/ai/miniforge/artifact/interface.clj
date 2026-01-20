(ns ai.miniforge.artifact.interface
  "Public API for the artifact component."
  (:require
   [ai.miniforge.artifact.core :as core]))

(def ArtifactStore core/ArtifactStore)

(defn create-store
  ([] (core/create-store))
  ([opts] (core/create-store opts)))

(defn close-store [store] (core/close store))
(defn save! [store artifact] (core/save store artifact))
(defn load-artifact [store id] (core/load-artifact store id))
(defn query [store criteria] (core/query store criteria))
(defn link! [store parent-id child-id] (core/link store parent-id child-id))

(defn build-artifact [opts] (core/build-artifact opts))
(defn add-parent [artifact parent-id] (core/add-parent artifact parent-id))
(defn add-child [artifact child-id] (core/add-child artifact child-id))

(defn get-provenance [store artifact-id]
  (when-let [artifact (load-artifact store artifact-id)]
    {:ancestors (:artifact/parents artifact [])
     :descendants (:artifact/children artifact [])}))

(defn query-by-type [store artifact-type]
  (query store {:artifact/type artifact-type}))

(defn query-by-origin [store origin-criteria]
  (let [all-artifacts (query store {})
        {task-id :task-id agent-id :agent-id} origin-criteria]
    (filter (fn [art]
              (let [origin (:artifact/origin art)]
                (or (and task-id (= task-id (:task-id origin)))
                    (and agent-id (= agent-id (:agent-id origin))))))
            all-artifacts)))
