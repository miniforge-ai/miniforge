(ns ai.miniforge.cli.workflow-runner.display
  "Terminal output formatting for workflow execution."
  (:require
   [clojure.pprint]
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
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
    (println (colorize :bold (messages/t :workflow-runner/header
                                         {:display-name (app-config/display-name)})))
    (println (colorize :cyan (messages/t :workflow-runner/workflow
                                         {:workflow-id (name workflow-id)})))
    (println (colorize :cyan (messages/t :workflow-runner/version
                                         {:version version})))
    (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))))

(defn format-event-line
  "Format a concise progress line for a lifecycle event. Returns nil for unknown events."
  [event]
  (let [evt (:event/type event)
        phase (or (:workflow/phase event) (:phase event))
        gate (or (:gate/id event) (:gate event))
        agent (or (:agent/id event) (:agent event))
        tool-id (:tool/id event)]
    (case evt
      :workflow/started (colorize :cyan (messages/t :workflow-runner/start))
      :workflow/completed (str (colorize :green (messages/t :workflow-runner/completed))
                               (when-let [d (:workflow/duration-ms event)]
                                 (str " (" (format-duration d) ")")))
      :workflow/failed (str (colorize :red (messages/t :workflow-runner/failed))
                            (when-let [r (:workflow/failure-reason event)]
                              (str ": " r)))
      :workflow/phase-started (colorize :yellow (messages/t :workflow-runner/phase-started
                                                            {:phase phase}))
      :workflow/phase-completed (str (colorize :green (messages/t :workflow-runner/phase-completed
                                                                   {:phase phase
                                                                    :outcome (name (or (:phase/outcome event)
                                                                                       :completed))}))
                                     (when-let [d (:phase/duration-ms event)]
                                       (str " (" (format-duration d) ")")))
      :workflow/milestone-reached (colorize :green (messages/t :workflow-runner/milestone
                                                                {:message (:message event)}))
      :agent/started (colorize :cyan (messages/t :workflow-runner/agent-started
                                                 {:agent agent}))
      :agent/completed (colorize :green (messages/t :workflow-runner/agent-completed
                                                    {:agent agent}))
      :agent/failed (colorize :red (messages/t :workflow-runner/agent-failed
                                               {:agent agent}))
      :agent/status (colorize :cyan (messages/t :workflow-runner/agent-status
                                                {:agent agent
                                                 :status (or (:message event)
                                                             (:status/type event)
                                                             (messages/t :workflow-runner/default-status))}))
      :tool/invoked (colorize :yellow (messages/t :workflow-runner/tool-invoked
                                                  {:tool-id tool-id}))
      :tool/completed (colorize :green (messages/t :workflow-runner/tool-completed
                                                   {:tool-id tool-id}))
      :gate/started (colorize :yellow (messages/t :workflow-runner/gate-started
                                                  {:gate gate}))
      :gate/passed (colorize :green (messages/t :workflow-runner/gate-passed
                                                {:gate gate}))
      :gate/failed (colorize :red (messages/t :workflow-runner/gate-failed
                                              {:gate gate}))
      :chain/completed (str (colorize :green (str "chain " (name (or (:chain/id event) :unknown)) " completed"))
                            (when-let [d (:chain/duration-ms event)]
                              (str " (" (format-duration d) ")")))
      nil)))

(defn format-demo-line
  "Format a plain-text (no ANSI) progress line for demo/test output.
   Uses '?' for nil values and 'unknown' for missing failure reasons."
  [event]
  (let [evt (:event/type event)
        phase (or (:workflow/phase event) (:phase event) "?")
        gate (or (:gate/id event) (:gate event) "?")
        agent (or (:agent/id event) (:agent event) "?")]
    (case evt
      :workflow/started "workflow started"
      :workflow/completed (str "workflow completed"
                               (if-let [d (:workflow/duration-ms event)]
                                 (str " (" (format-duration d) ")")
                                 " (?)"))
      :workflow/failed (str "workflow failed: "
                            (or (:workflow/failure-reason event) "unknown"))
      :workflow/phase-started (str "phase " phase " started")
      :workflow/phase-completed (str "phase " phase " completed"
                                     (if-let [d (:phase/duration-ms event)]
                                       (str " (" (format-duration d) ")")
                                       " (?)"))
      :workflow/milestone-reached (str "milestone: " (:message event "?"))
      :agent/started (str "agent " agent " started")
      :agent/completed (str "agent " agent " completed")
      :agent/failed (str "agent " agent " failed")
      :agent/status (str "agent " agent " status")
      :tool/invoked (str "tool " (or (:tool/id event) "?") " invoked")
      :tool/completed (str "tool " (or (:tool/id event) "?") " completed")
      :gate/started (str "gate " gate " started")
      :gate/passed (str "gate " gate " passed")
      :gate/failed (str "gate " gate " failed")
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

(defn print-workflow-summary [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)]
    (println (if success?
               (colorize :green (messages/t :workflow-runner/summary-success))
               (colorize :red (messages/t :workflow-runner/summary-failure))))
    (when metrics
      (println (messages/t :workflow-runner/metrics
                           {:tokens (:tokens metrics 0)
                            :cost (format "%.4f" (:cost-usd metrics 0.0))
                            :duration (format-duration (:duration-ms metrics 0))})))
    (when (seq errors)
      (println (colorize :red (str "\n" (messages/t :workflow-runner/errors))))
      (doseq [err errors]
        (println (str "  • " err))))))

(defn print-pretty-result [result]
  (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
  (print-workflow-summary result)
  (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))
  (println (str "\n" (messages/t :workflow-runner/full-result)))
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
  (println (colorize :red (str "\n" (messages/t :workflow-runner/load-failed))))
  (println (messages/t :workflow-runner/error {:message msg}))
  (when data
    (println (messages/t :workflow-runner/details {:details (pr-str data)})))
  (when cause
    (println (messages/t :workflow-runner/cause {:cause (ex-message cause)})))
  (println (colorize :yellow (str "\n" (messages/t :workflow-runner/possible-causes))))
  (println (messages/t :workflow-runner/cause-missing-dep))
  (println (messages/t :workflow-runner/cause-compile))
  (println (messages/t :workflow-runner/cause-cycle)))

(defn print-namespace-resolution-help
  "Print help for namespace resolution errors."
  []
  (println (colorize :cyan (str "\n" (messages/t :workflow-runner/namespace-help-header))))
  (println (messages/t :workflow-runner/namespace-help-dep))
  (println (messages/t :workflow-runner/namespace-help-build)))

(defn print-babashka-fallback-help
  "Print help for Babashka compatibility issues."
  []
  (println (colorize :cyan (str "\n" (messages/t :workflow-runner/bb-help-header))))
  (println (messages/t :workflow-runner/bb-help-command)))

(defn print-general-debugging-help
  "Print general debugging tips."
  []
  (println (colorize :cyan (str "\n" (messages/t :workflow-runner/debug-header))))
  (println (messages/t :workflow-runner/debug-command)))
