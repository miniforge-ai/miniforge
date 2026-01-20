(ns ai.miniforge.reporting.views
  "ANSI terminal view renderers for reporting output."
  (:require
   [clojure.string :as str]
   [clojure.pprint :as pprint]))

;------------------------------------------------------------------------------ Layer 0
;; ANSI color codes

(def ansi-codes
  {:reset "\033[0m"
   :bold "\033[1m"
   :black "\033[30m"
   :red "\033[31m"
   :green "\033[32m"
   :yellow "\033[33m"
   :blue "\033[34m"
   :magenta "\033[35m"
   :cyan "\033[36m"
   :white "\033[37m"
   :bg-black "\033[40m"
   :bg-red "\033[41m"
   :bg-green "\033[42m"
   :bg-yellow "\033[43m"
   :bg-blue "\033[44m"})

(defn ansi
  "Apply ANSI color/formatting to text."
  [code text]
  (str (get ansi-codes code "") text (:reset ansi-codes)))

(defn status-color
  "Get color for a status keyword."
  [status]
  (case status
    (:completed :running :active :ok) :green
    (:pending :idle) :yellow
    (:failed :error) :red
    :white))

;------------------------------------------------------------------------------ Layer 1
;; Box drawing helpers

(def box-chars
  {:horizontal "─"
   :vertical "│"
   :top-left "┌"
   :top-right "┐"
   :bottom-left "└"
   :bottom-right "┘"
   :cross "┼"
   :t-down "┬"
   :t-up "┴"
   :t-right "├"
   :t-left "┤"})

(defn draw-box
  "Draw a box around content."
  [title content width]
  (let [title-str (str " " title " ")
        title-len (count title-str)
        padding (max 0 (- width title-len 2))
        top-line (str (:top-left box-chars)
                      title-str
                      (apply str (repeat padding (:horizontal box-chars)))
                      (:top-right box-chars))
        bottom-line (str (:bottom-left box-chars)
                         (apply str (repeat (- width 2) (:horizontal box-chars)))
                         (:bottom-right box-chars))
        content-lines (str/split-lines content)]
    (str/join "\n"
              (concat
               [top-line]
               (map (fn [line]
                      (let [line-len (count line)
                            pad (max 0 (- width line-len 2))]
                        (str (:vertical box-chars)
                             line
                             (apply str (repeat pad " "))
                             (:vertical box-chars))))
                    content-lines)
               [bottom-line]))))

(defn draw-separator
  "Draw a horizontal separator."
  [width]
  (apply str (repeat width (:horizontal box-chars))))

;------------------------------------------------------------------------------ Layer 2
;; Table formatting

(defn format-table
  "Format data as a table."
  [headers rows]
  (let [col-count (count headers)
        col-widths (map (fn [idx]
                          (apply max
                                 (count (nth headers idx))
                                 (map #(count (str (nth % idx ""))) rows)))
                        (range col-count))
        format-row (fn [row]
                     (str/join " │ "
                               (map-indexed
                                (fn [idx val]
                                  (let [width (nth col-widths idx)
                                        val-str (str val)]
                                    (str val-str
                                         (apply str (repeat (- width (count val-str)) " ")))))
                                row)))
        header-line (format-row headers)
        separator (str/join "─┼─"
                            (map #(apply str (repeat % "─")) col-widths))]
    (str/join "\n"
              (concat
               [header-line separator]
               (map format-row rows)))))

;------------------------------------------------------------------------------ Layer 3
;; System overview renderer

(defn render-system-overview
  "Render system overview with boxed display."
  [status-data]
  (let [workflows (:workflows status-data)
        resources (:resources status-data)
        meta-loop (:meta-loop status-data)
        alerts (:alerts status-data)
        
        ;; Workflow section
        wf-section (str/join "\n"
                             [(str "Active:    " (ansi :green (str (:active workflows 0))))
                              (str "Pending:   " (ansi :yellow (str (:pending workflows 0))))
                              (str "Completed: " (ansi :green (str (:completed workflows 0))))
                              (str "Failed:    " (ansi :red (str (:failed workflows 0))))])
        
        ;; Resources section
        res-section (str/join "\n"
                              [(str "Tokens Used: " (:tokens-used resources 0))
                               (str "Cost (USD):  $" (format "%.4f" (:cost-usd resources 0.0)))])
        
        ;; Meta-loop section
        ml-status (:status meta-loop :not-configured)
        ml-color (status-color ml-status)
        ml-section (str/join "\n"
                             [(str "Status: " (ansi ml-color (name ml-status)))
                              (str "Pending Improvements: " (:pending-improvements meta-loop 0))])
        
        ;; Alerts section
        alerts-section (if (empty? alerts)
                         (ansi :green "No alerts")
                         (str/join "\n"
                                   (map (fn [alert]
                                          (let [color (case (:severity alert)
                                                        :error :red
                                                        :warning :yellow
                                                        :info :blue
                                                        :white)]
                                            (str (ansi color (str "[" (name (:severity alert)) "]"))
                                                 " " (:message alert))))
                                        alerts)))
        
        width 60]
    
    (str/join "\n\n"
              [(draw-box (ansi :bold "WORKFLOWS") wf-section width)
               (draw-box (ansi :bold "RESOURCES") res-section width)
               (draw-box (ansi :bold "META-LOOP") ml-section width)
               (draw-box (ansi :bold "ALERTS") alerts-section width)])))

;------------------------------------------------------------------------------ Layer 4
;; Workflow list renderer

(defn render-workflow-list
  "Render workflow list as table."
  [workflows]
  (if (empty? workflows)
    (ansi :yellow "No workflows found.")
    (let [headers ["ID" "Status" "Phase" "Created"]
          rows (map (fn [wf]
                      (let [id-str (subs (str (:workflow/id wf)) 0 8)
                            status-str (name (:workflow/status wf))
                            status-colored (ansi (status-color (:workflow/status wf))
                                                 status-str)
                            phase-str (name (:workflow/phase wf :unknown))
                            created-str (str (:workflow/created-at wf "-"))]
                        [id-str status-colored phase-str created-str]))
                    workflows)]
      (format-table headers rows))))

;------------------------------------------------------------------------------ Layer 5
;; Workflow detail renderer

(defn render-workflow-detail
  "Render detailed workflow view."
  [detail]
  (if-not detail
    (ansi :red "Workflow not found.")
    (let [header (:header detail)
          timeline (:timeline detail)
          current-task (:current-task detail)
          artifacts (:artifacts detail)
          logs (:logs detail)
          
          ;; Header section
          header-section (str/join "\n"
                                   [(str "ID:      " (:id header))
                                    (str "Status:  " (ansi (status-color (:status header))
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
                                               (ansi :cyan (name (:phase entry)))
                                               " - "
                                               (ansi (status-color (:status entry))
                                                     (name (:status entry)))))
                                        timeline)))
          
          ;; Current task section
          task-section (str/join "\n"
                                 [(str "Description: " (:description current-task "N/A"))
                                  (str "Agent:       " (:agent current-task "N/A"))
                                  (str "Status:      " (ansi (status-color (:status current-task))
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
                [(draw-box (ansi :bold "WORKFLOW HEADER") header-section width)
                 (draw-box (ansi :bold "TIMELINE") timeline-section width)
                 (draw-box (ansi :bold "CURRENT TASK") task-section width)
                 (draw-box (ansi :bold "ARTIFACTS") artifacts-section width)]))))

;------------------------------------------------------------------------------ Layer 6
;; Meta-loop status renderer

(defn render-meta-loop
  "Render meta-loop dashboard."
  [meta-status]
  (let [signals (:signals meta-status)
        pending (:pending-improvements meta-status)
        recent (:recent-improvements meta-status)
        
        ;; Signals section
        signals-section (if (empty? signals)
                          "No recent signals"
                          (str/join "\n"
                                    (map (fn [sig]
                                           (str "- " (ansi :cyan (name (:signal/type sig)))
                                                " at " (:signal/timestamp sig)))
                                         (take 10 signals))))
        
        ;; Pending improvements section
        pending-section (if (empty? pending)
                          (ansi :green "No pending improvements")
                          (str/join "\n"
                                    (map (fn [imp]
                                           (str "- " (ansi :yellow (name (:improvement/type imp)))
                                                " (confidence: "
                                                (format "%.2f" (:improvement/confidence imp 0.0))
                                                ")\n  "
                                                (:improvement/rationale imp)))
                                         pending)))
        
        ;; Recent improvements section
        recent-section (if (empty? recent)
                         "No recent improvements"
                         (str/join "\n"
                                   (map (fn [imp]
                                          (str "- " (ansi :green (name (:improvement/type imp)))
                                               " applied at " (:improvement/applied-at imp)))
                                        (take 10 recent))))
        
        width 80]
    
    (str/join "\n\n"
              [(draw-box (ansi :bold "RECENT SIGNALS") signals-section width)
               (draw-box (ansi :bold "PENDING IMPROVEMENTS") pending-section width)
               (draw-box (ansi :bold "RECENT IMPROVEMENTS") recent-section width)])))

;------------------------------------------------------------------------------ Layer 7
;; EDN renderer

(defn render-edn
  "Render data as machine-readable EDN."
  [data]
  (with-out-str
    (pprint/pprint data)))
