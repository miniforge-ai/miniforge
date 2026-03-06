(ns ai.miniforge.cli.workflow-runner.display
  "Terminal output formatting for workflow execution."
  (:require
   [clojure.pprint]
   [cheshire.core :as json]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; ANSI color primitives

(def ansi-codes
  {:reset "\u001b[0m"
   :bold "\u001b[1m"
   :cyan "\u001b[36m"
   :green "\u001b[32m"
   :yellow "\u001b[33m"
   :red "\u001b[31m"})

(defn colorize [color text]
  (str (get ansi-codes color "") text (:reset ansi-codes)))

(defn format-duration [ms]
  (cond
    (< ms 1000) (str ms "ms")
    (< ms 60000) (format "%.1fs" (/ ms 1000.0))
    :else (format "%.1fm" (/ ms 60000.0))))

;------------------------------------------------------------------------------ Layer 1
;; Workflow progress output

(defn print-workflow-header [workflow-id version quiet?]
  (when-not quiet?
    (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
    (println (colorize :bold "  Miniforge Workflow Runner"))
    (println (str "  Workflow: " (colorize :cyan (name workflow-id))))
    (println (str "  Version:  " (colorize :cyan version)))
    (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))))

(defn- format-event-line
  "Format a concise progress line for a lifecycle event. Returns nil for unknown events."
  [event]
  (let [evt (:event/type event)
        phase (or (:workflow/phase event) (:phase event))
        gate (or (:gate/id event) (:gate event))
        agent (or (:agent/id event) (:agent event))
        tool-id (:tool/id event)]
    (case evt
      :workflow/started (str (colorize :cyan "▶") " Workflow started")
      :workflow/completed (str (colorize :green "✓") " Workflow completed"
                               (when-let [d (:workflow/duration-ms event)]
                                 (str " (" (format-duration d) ")")))
      :workflow/failed (str (colorize :red "✗") " Workflow failed"
                            (when-let [r (:workflow/failure-reason event)]
                              (str ": " r)))
      :workflow/phase-started (str (colorize :yellow "→") " Phase " phase " started")
      :workflow/phase-completed (str (colorize :green "✓") " Phase " phase " "
                                     (name (or (:phase/outcome event) :completed))
                                     (when-let [d (:phase/duration-ms event)]
                                       (str " (" (format-duration d) ")")))
      :workflow/milestone-reached (str (colorize :green "★") " Milestone: "
                                       (:message event))
      :agent/started (str "  " (colorize :cyan "•") " Agent " agent " started")
      :agent/completed (str "  " (colorize :green "•") " Agent " agent " completed")
      :agent/failed (str "  " (colorize :red "•") " Agent " agent " failed")
      :agent/status (str "  " (colorize :cyan "•") " Agent " agent ": "
                         (or (:message event) (:status/type event) "working"))
      :tool/invoked (str "  " (colorize :yellow "•") " Tool " tool-id " invoked")
      :tool/completed (str "  " (colorize :green "•") " Tool " tool-id " completed")
      :gate/started (str "  " (colorize :yellow "•") " Gate " gate " running")
      :gate/passed (str "  " (colorize :green "•") " Gate " gate " passed")
      :gate/failed (str "  " (colorize :red "•") " Gate " gate " failed")
      nil)))

(defn start-progress!
  "Subscribe to lifecycle events and print concise progress lines.
   Returns a cleanup function."
  [event-stream quiet?]
  (if (or quiet? (nil? event-stream))
    (fn [] nil)
    (let [sub-id (keyword (str "progress-" (random-uuid)))
          last-line (atom nil)]
      (es/subscribe! event-stream sub-id
                     (fn [event]
                       (when-let [line (format-event-line event)]
                         ;; Deduplicate back-to-back duplicates from layered emitters.
                         (when-not (= line @last-line)
                           (reset! last-line line)
                           (println line)))))
      (fn []
        (es/unsubscribe! event-stream sub-id)))))

(defn- print-workflow-summary [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)]
    (println (if success?
               (colorize :green "✓ Workflow completed")
               (colorize :red "✗ Workflow failed")))
    (when metrics
      (println (str "Tokens: " (:tokens metrics 0)
                    " | Cost: $" (format "%.4f" (:cost-usd metrics 0.0))
                    " | Duration: " (format-duration (:duration-ms metrics 0)))))
    (when (seq errors)
      (println (colorize :red "\nErrors:"))
      (doseq [err errors]
        (println (str "  • " err))))))

(defn- print-pretty-result [result]
  (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
  (print-workflow-summary result)
  (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))
  (println "\nFull result:")
  (clojure.pprint/pprint result))

(defn print-result [result {:keys [output quiet]}]
  (case output
    :json (println (json/generate-string result {:pretty true}))
    :pretty (when-not quiet (print-pretty-result result))
    (clojure.pprint/pprint result)))

;------------------------------------------------------------------------------ Layer 2
;; Error help output

(defn print-error-header
  "Print error header with message, details, and cause."
  [msg data cause]
  (println (colorize :red "\n✗ Failed to load workflow interface"))
  (println (str "  Error: " msg))
  (when data
    (println (str "  Details: " (pr-str data))))
  (when cause
    (println (str "  Cause: " (ex-message cause))))
  (println (colorize :yellow "\nPossible causes:"))
  (println "  - Missing dependency in deps.edn: ai.miniforge/workflow")
  (println "  - Namespace compilation error in workflow component")
  (println "  - Circular dependency issue"))

(defn print-namespace-resolution-help
  "Print help for namespace resolution errors."
  []
  (println (colorize :cyan "\nIf the namespace doesn't exist:"))
  (println "  - Check that ai.miniforge/workflow is in your deps.edn")
  (println "  - Verify the component was built: clojure -M:poly test"))

(defn print-babashka-fallback-help
  "Print help for Babashka compatibility issues."
  []
  (println (colorize :cyan "\nIf running with Babashka (bb):"))
  (println "  - Try the JVM version: clojure -M:dev -m ai.miniforge.cli.main workflow run <id>"))

(defn print-general-debugging-help
  "Print general debugging tips."
  []
  (println (colorize :cyan "\nFor debugging:"))
  (println "  - Run with verbose output: bb -e '(requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)'"))
