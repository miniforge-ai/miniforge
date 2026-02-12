(ns ai.miniforge.cli.workflow-runner.dashboard
  "Dashboard auto-discovery, event bridging, and command polling."
  (:require
   [cheshire.core :as json]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Auto-discovery

(defn discover-dashboard-url
  "Auto-discover running dashboard from ~/.miniforge/dashboard.port.
   Returns dashboard URL string or nil."
  []
  (try
    (let [discovery-file (str (System/getProperty "user.home") "/.miniforge/dashboard.port")]
      (when (.exists (java.io.File. discovery-file))
        (let [info (json/parse-string (slurp discovery-file) true)
              port (:port info)
              url (str "http://localhost:" port)]
          ;; Verify dashboard is actually running via health check
          (try
            (let [http-get (requiring-resolve 'babashka.http-client/get)
                  response (http-get (str url "/health") {:timeout 2000 :throw false})]
              (when (= 200 (:status response))
                url))
            (catch Exception _ nil)))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Event bridging

(defn bridge-events-to-dashboard!
  "Subscribe to event stream and forward events to dashboard via HTTP POST.
   Returns a cleanup function, or nil if dashboard not available."
  [event-stream dashboard-url quiet]
  (when dashboard-url
    (try
      (let [http-post (requiring-resolve 'babashka.http-client/post)
            ingest-url (str dashboard-url "/api/events/ingest")]
        (when-not quiet
          (println (display/colorize :cyan (str "   Dashboard: " dashboard-url))))
        ;; Subscribe to event stream — POST each event to dashboard
        (es/subscribe! event-stream :dashboard-bridge
                       (fn [event]
                         (try
                           (http-post ingest-url
                                      {:headers {"Content-Type" "application/json"}
                                       :body (json/generate-string event)
                                       :timeout 5000
                                       :throw false})
                           (catch Exception _ nil))))
        ;; Return cleanup fn
        (fn []
          (es/unsubscribe! event-stream :dashboard-bridge)))
      (catch Exception e
        (when-not quiet
          (println (display/colorize :yellow (str "   Dashboard: not connected (" (ex-message e) ")"))))
        nil))))

;------------------------------------------------------------------------------ Layer 2
;; Command polling

(defn start-command-poller!
  "Start a background thread that polls the dashboard for pending commands.
   Updates control-state atom when commands arrive.
   Returns a cleanup function."
  [dashboard-url workflow-id control-state]
  (when dashboard-url
    (let [running (atom true)
          http-get (requiring-resolve 'babashka.http-client/get)
          poll-url (str dashboard-url "/api/workflow/" workflow-id "/commands")
          thread (Thread.
                  (fn []
                    (while @running
                      (try
                        (Thread/sleep 2000)
                        (when @running
                          (let [response (http-get poll-url {:timeout 3000 :throw false})
                                body (when (= 200 (:status response))
                                       (json/parse-string (:body response) true))
                                commands (:commands body)]
                            (doseq [cmd commands]
                              (let [command (keyword (:command cmd))]
                                (case command
                                  :pause (do (swap! control-state assoc :paused true)
                                             (println "⏸  Workflow paused by dashboard"))
                                  :resume (do (swap! control-state assoc :paused false)
                                              (println "▶  Workflow resumed by dashboard"))
                                  :stop (do (swap! control-state assoc :stopped true)
                                            (println "⏹  Workflow stopped by dashboard"))
                                  nil)))))
                        (catch InterruptedException _
                          (reset! running false))
                        (catch Exception _ nil)))))]
      (.setDaemon thread true)
      (.start thread)
      (fn []
        (reset! running false)
        (.interrupt thread)))))
