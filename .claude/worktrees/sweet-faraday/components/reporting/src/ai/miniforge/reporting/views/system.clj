(ns ai.miniforge.reporting.views.system
  "System overview rendering.

   Renders overall system status and health information."
  (:require [ai.miniforge.reporting.views.formatting :as fmt]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; System overview rendering

(defn render-system-overview
  "Render system overview with boxed display.

   Args:
     status-data - Map containing:
       :workflows - Map with :active, :pending, :completed, :failed counts
       :resources - Map with :tokens-used, :cost-usd
       :meta-loop - Map with :status, :pending-improvements
       :alerts    - Sequence of alert maps with :severity, :message

   Returns:
     Formatted string with 4 boxes (WORKFLOWS, RESOURCES, META-LOOP, ALERTS)."
  [status-data]
  (let [workflows (:workflows status-data)
        resources (:resources status-data)
        meta-loop (:meta-loop status-data)
        alerts (:alerts status-data)

        ;; Workflow section
        wf-section (str/join "\n"
                             [(str "Active:    " (fmt/ansi :green (str (:active workflows 0))))
                              (str "Pending:   " (fmt/ansi :yellow (str (:pending workflows 0))))
                              (str "Completed: " (fmt/ansi :green (str (:completed workflows 0))))
                              (str "Failed:    " (fmt/ansi :red (str (:failed workflows 0))))])

        ;; Resources section
        res-section (str/join "\n"
                              [(str "Tokens Used: " (:tokens-used resources 0))
                               (str "Cost (USD):  $" (format "%.4f" (:cost-usd resources 0.0)))])

        ;; Meta-loop section
        ml-status (:status meta-loop :not-configured)
        ml-color (fmt/status-color ml-status)
        ml-section (str/join "\n"
                             [(str "Status: " (fmt/ansi ml-color (name ml-status)))
                              (str "Pending Improvements: " (:pending-improvements meta-loop 0))])

        ;; Alerts section
        alerts-section (if (empty? alerts)
                         (fmt/ansi :green "No alerts")
                         (str/join "\n"
                                   (map (fn [alert]
                                          (let [color (case (:severity alert)
                                                        :error :red
                                                        :warning :yellow
                                                        :info :blue
                                                        :white)]
                                            (str (fmt/ansi color (str "[" (name (:severity alert)) "]"))
                                                 " " (:message alert))))
                                        alerts)))

        width 60]

    (str/join "\n\n"
              [(fmt/draw-box (fmt/ansi :bold "WORKFLOWS") wf-section width)
               (fmt/draw-box (fmt/ansi :bold "RESOURCES") res-section width)
               (fmt/draw-box (fmt/ansi :bold "META-LOOP") ml-section width)
               (fmt/draw-box (fmt/ansi :bold "ALERTS") alerts-section width)])))

(comment
  ;; Test system overview rendering
  (println (render-system-overview
            {:workflows {:active 2 :pending 1 :completed 10 :failed 1}
             :resources {:tokens-used 5000 :cost-usd 0.25}
             :meta-loop {:status :active :pending-improvements 3}
             :alerts [{:type :failed-workflows
                      :severity :error
                      :message "1 failed workflow(s)"}]}))

  (println (render-system-overview
            {:workflows {} :resources {} :meta-loop {} :alerts []}))

  :end)
