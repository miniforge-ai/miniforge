(ns ai.miniforge.workflow.persistence
  "Persistence layer for workflow configuration.
   Handles filesystem I/O for active workflow management.

   Architecture:
   - Layer 0: Pure functions for path handling
   - Layer 1: File I/O operations (load/save)"
  (:require
   [clojure.edn]
   [clojure.java.io]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions for path handling

(defn active-config-path
  "Get path to active workflow config file.
   Defaults to ~/.miniforge/workflows/active.edn"
  []
  (let [home (System/getProperty "user.home")
        config-dir (str home "/.miniforge/workflows")]
    (str config-dir "/active.edn")))

(defn ensure-config-dir
  "Ensure the config directory exists.
   Returns the directory path."
  []
  (let [home (System/getProperty "user.home")
        config-dir (str home "/.miniforge/workflows")]
    (.mkdirs (java.io.File. config-dir))
    config-dir))

(defn event-log-path
  "Get path to event log file for a workflow.
   Format: ~/.miniforge/workflows/events/<workflow-id>.edn"
  [workflow-id]
  (let [config-dir (ensure-config-dir)
        events-dir (str config-dir "/events")]
    (.mkdirs (java.io.File. events-dir))
    (str events-dir "/" workflow-id ".edn")))

;------------------------------------------------------------------------------ Layer 1
;; File I/O operations

(defn load-active-config
  "Load active workflow configuration from ~/.miniforge/workflows/active.edn

   Returns map of task-type -> {:workflow-id :version}
   Returns empty map if file doesn't exist or on error."
  []
  (let [path (active-config-path)]
    (try
      (when (.exists (java.io.File. path))
        (with-open [rdr (java.io.PushbackReader. (clojure.java.io/reader path))]
          (clojure.edn/read rdr)))
      (catch Exception _e
        ;; Return empty config on error
        {}))))

(defn save-active-config
  "Save active workflow configuration to ~/.miniforge/workflows/active.edn

   Arguments:
   - config: Map of task-type -> {:workflow-id :version}

   Returns true on success, false on error."
  [config]
  (ensure-config-dir)
  (let [path (active-config-path)]
    (try
      (with-open [wtr (clojure.java.io/writer path)]
        (binding [*print-length* nil
                  *print-level* nil]
          (.write wtr (pr-str config))))
      true
      (catch Exception _e
        false))))

(defn get-active-workflow-id
  "Get the active workflow ID for a task type.

   Arguments:
   - task-type: Task type keyword (e.g., :feature, :bugfix)

   Returns map {:workflow-id :version} or nil if not set."
  [task-type]
  (let [config (load-active-config)]
    (get config task-type)))

(defn set-active-workflow
  "Set the active workflow for a task type.

   Arguments:
   - task-type: Task type keyword (e.g., :feature, :bugfix)
   - workflow-id: Workflow identifier
   - version: Workflow version

   Returns true on success, false on error."
  [task-type workflow-id version]
  (let [config (load-active-config)
        new-config (assoc config task-type {:workflow-id workflow-id
                                            :version version})]
    (save-active-config new-config)))

;------------------------------------------------------------------------------ Layer 2
;; Event log persistence

(defn append-event
  "Append an event to a workflow's event log.
   Events are appended to ~/.miniforge/workflows/events/<workflow-id>.edn
   
   Arguments:
   - workflow-id: Workflow UUID
   - event: Log event map
   
   Returns true on success, false on error."
  [workflow-id event]
  (let [path (event-log-path workflow-id)]
    (try
      (with-open [wtr (clojure.java.io/writer path :append true)]
        (binding [*print-length* nil
                  *print-level* nil]
          (.write wtr (pr-str event))
          (.write wtr "\n")))
      true
      (catch Exception _e
        false))))

(defn load-event-log
  "Load all events for a workflow from its event log.
   
   Arguments:
   - workflow-id: Workflow UUID
   
   Returns vector of event maps, or empty vector if log doesn't exist."
  [workflow-id]
  (let [path (event-log-path workflow-id)]
    (try
      (if (.exists (java.io.File. path))
        (with-open [rdr (java.io.PushbackReader. (clojure.java.io/reader path))]
          (loop [events []]
            (let [event (try
                          (clojure.edn/read rdr)
                          (catch java.io.EOFException _eof nil))]
              (if event
                (recur (conj events event))
                events))))
        [])
      (catch Exception _e
        []))))
