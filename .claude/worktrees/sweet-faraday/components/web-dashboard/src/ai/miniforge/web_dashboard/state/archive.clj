(ns ai.miniforge.web-dashboard.state.archive
  "Archived workflow state — queries, deletion, retention."
  (:require
   [clojure.java.io :as io]
   [ai.miniforge.web-dashboard.watcher :as watcher]
   [ai.miniforge.web-dashboard.state.workflows :as workflows]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn normalize-ts
  "Normalize timestamp to java.util.Date for comparison."
  [ts]
  (cond
    (instance? java.util.Date ts)    ts
    (instance? java.time.Instant ts) (java.util.Date/from ts)
    (string? ts) (try (java.util.Date/from (java.time.Instant/parse ts))
                      (catch Exception _ nil))
    :else nil))

(defn ts->epoch-ms
  "Timestamp → epoch millis, or 0 if unparseable."
  [ts]
  (if-let [d (normalize-ts ts)] (.getTime d) 0))

(defn exclude-live-ids
  "Remove any archived workflows whose IDs also appear in the live list.
   Arg order supports ->> threading: live-workflows first, archived-vals last."
  [live-workflows archived-vals]
  (let [live-ids (->> live-workflows (map (comp str :id)) set)]
    (remove #(contains? live-ids (str (:id %))) archived-vals)))

(defn newest-first
  "Sort workflows by :started-at descending."
  [workflows]
  (sort-by #(- (ts->epoch-ms (:started-at %))) workflows))

;------------------------------------------------------------------------------ Layer 1
;; Queries

(defn archive-loading?
  "True while the background archive scan is still running."
  [state]
  (let [flag (:archive-loading? @state)]
    (if flag @flag false)))

(defn get-archived-workflows
  "Archived workflows sorted newest-first, excluding any in the live list."
  [state]
  (let [archive-atom (:archived-workflows @state)
        archived     (when archive-atom (vals @archive-atom))
        live         (workflows/get-workflows state)]
    (->> archived
         (exclude-live-ids live)
         newest-first
         vec)))

(defn get-archived-workflow-events
  "Read full events from an archived workflow's .edn file on demand.
   Returns events vector (most recent first, limited to 200)."
  [state workflow-id]
  (let [archive-atom (:archived-workflows @state)
        summary      (when archive-atom (get @archive-atom (str workflow-id)))]
    (if-let [file-path (:file-path summary)]
      (try
        (let [file (io/file file-path)]
          (when (.exists file)
            (with-open [rdr (io/reader file)]
              (->> (line-seq rdr)
                   (keep watcher/parse-edn-line)
                   reverse
                   (take 200)
                   vec))))
        (catch Exception _ []))
      [])))

;------------------------------------------------------------------------------ Layer 2
;; Mutations

(defn delete-archived-workflow!
  "Remove an archived workflow from state and delete its .edn file."
  [state workflow-id]
  (let [archive-atom (:archived-workflows @state)
        wf-id-str    (str workflow-id)
        summary      (get @archive-atom wf-id-str)]
    (swap! archive-atom dissoc wf-id-str)
    (when-let [path (:file-path summary)]
      (try
        (let [f (io/file path)]
          (when (.exists f) (.delete f)))
        (catch Exception _ nil)))
    true))

(defn apply-retention!
  "Delete archived workflows older than max-age-days. Returns count deleted."
  [state max-age-days]
  (let [archive-atom (:archived-workflows @state)
        cutoff-ms    (* max-age-days 24 60 60 1000)
        now-ms       (System/currentTimeMillis)
        to-delete    (->> (vals @archive-atom)
                          (filter #(let [ms (ts->epoch-ms (:started-at %))]
                                     (and (pos? ms) (> (- now-ms ms) cutoff-ms)))))]
    (doseq [wf to-delete]
      (delete-archived-workflow! state (:id wf)))
    (count to-delete)))
