(ns ai.miniforge.reporting.views.workflow
  "Workflow rendering - list and detail views.

   Renders workflow information in both summary and detailed formats."
  (:require [ai.miniforge.reporting.views.formatting :as fmt]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Workflow list rendering

(defn render-workflow-list
  "Render workflow list as table.

   Args:
     workflows - Sequence of workflow maps with namespaced keys:
       :workflow/id         - UUID
       :workflow/status     - Status keyword
       :workflow/phase      - Phase keyword
       :workflow/created-at - Timestamp

   Returns:
     Formatted table string or \"No workflows found.\" message."
  [workflows]
  (if (empty? workflows)
    (fmt/ansi :yellow "No workflows found.")
    (let [headers ["ID" "Status" "Phase" "Created"]
          rows (map (fn [wf]
                      (let [id-str (subs (str (:workflow/id wf)) 0 8)
                            status-str (name (:workflow/status wf))
                            status-colored (fmt/ansi (fmt/status-color (:workflow/status wf))
                                                     status-str)
                            phase-str (name (:workflow/phase wf :unknown))
                            created-str (str (:workflow/created-at wf "-"))]
                        [id-str status-colored phase-str created-str]))
                    workflows)]
      (fmt/format-table headers rows))))

;------------------------------------------------------------------------------ Layer 1
;; Workflow detail rendering

(defn render-workflow-detail
  "Render detailed workflow view.

   Args:
     detail - Map containing:
       :header       - Map with :id, :status, :phase, :created-at, :updated-at
       :timeline     - Sequence of phase entries with :phase, :status, :started-at, :completed-at
       :current-task - Map with :description, :agent, :status
       :artifacts    - Sequence of artifact maps with :id, :type, :created-at
       :logs         - Sequence of log entries

   Returns:
     Formatted string with 4 boxes (HEADER, TIMELINE, CURRENT TASK, ARTIFACTS) or \"Workflow not found.\" message."
  [detail]
  (if-not detail
    (fmt/ansi :red "Workflow not found.")
    (let [header (:header detail)
          timeline (:timeline detail)
          current-task (:current-task detail)
          artifacts (:artifacts detail)
          _logs (:logs detail)

          ;; Header section
          header-section (str/join "\n"
                                   [(str "ID:      " (:id header))
                                    (str "Status:  " (fmt/ansi (fmt/status-color (:status header))
                                                                (name (:status header))))
                                    (str "Phase:   " (name (:phase header)))
                                    (str "Created: " (:created-at header))
                                    (str "Updated: " (:updated-at header))])

          ;; Timeline section
          timeline-section (if (empty? timeline)
                             "No timeline data"
                             (str/join "\n"
                                       (map-indexed
                                        (fn [idx entry]
                                          (str (inc idx) ". "
                                               (fmt/ansi :cyan (name (:phase entry)))
                                               " - "
                                               (fmt/ansi (fmt/status-color (:status entry))
                                                         (name (:status entry)))))
                                        timeline)))

          ;; Current task section
          task-section (str/join "\n"
                                 [(str "Description: " (:description current-task "N/A"))
                                  (str "Agent:       " (:agent current-task "N/A"))
                                  (str "Status:      " (fmt/ansi (fmt/status-color (:status current-task))
                                                                  (name (:status current-task :unknown))))])

          ;; Artifacts section
          artifacts-section (if (empty? artifacts)
                              "No artifacts"
                              (str/join "\n"
                                        (map (fn [art]
                                               (str "- " (name (:type art))
                                                    " (" (subs (str (:id art)) 0 8) "...)"))
                                             artifacts)))

          width 80]

      (str/join "\n\n"
                [(fmt/draw-box (fmt/ansi :bold "WORKFLOW HEADER") header-section width)
                 (fmt/draw-box (fmt/ansi :bold "TIMELINE") timeline-section width)
                 (fmt/draw-box (fmt/ansi :bold "CURRENT TASK") task-section width)
                 (fmt/draw-box (fmt/ansi :bold "ARTIFACTS") artifacts-section width)]))))

(comment
  ;; Test workflow list rendering
  (println (render-workflow-list
            [{:workflow/id #uuid "12345678-1234-1234-1234-123456789012"
              :workflow/status :running
              :workflow/phase :implement
              :workflow/created-at 1234567890000}
             {:workflow/id #uuid "87654321-4321-4321-4321-210987654321"
              :workflow/status :completed
              :workflow/phase :done
              :workflow/created-at 1234567890000}]))

  (println (render-workflow-list []))

  ;; Test workflow detail rendering
  (println (render-workflow-detail
            {:header {:id #uuid "12345678-1234-1234-1234-123456789012"
                     :status :running
                     :phase :implement
                     :created-at 1234567890000
                     :updated-at 1234567890000}
             :timeline [{:phase :spec :status :completed :started-at 100 :completed-at 200}
                       {:phase :plan :status :completed :started-at 200 :completed-at 300}
                       {:phase :implement :status :running :started-at 300 :completed-at nil}]
             :current-task {:description "Implement feature"
                           :agent :implementer
                           :status :running}
             :artifacts [{:id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                         :type :code
                         :created-at 1234567890000}]
             :logs []}))

  (println (render-workflow-detail nil))

  :end)
