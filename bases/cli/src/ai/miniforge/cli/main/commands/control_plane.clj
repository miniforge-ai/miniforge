;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.cli.main.commands.control-plane
  "Control Plane CLI commands.

   Communicates with the running web dashboard's control plane API
   via HTTP to show agent status, pending decisions, and resolve decisions.

   Layer 0: Dashboard discovery
   Layer 1: HTTP client helpers
   Layer 2: CLI commands"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Dashboard discovery

(defn- discover-dashboard-url
  "Read the dashboard port from discovery file.
   Returns base URL string or nil."
  []
  (let [discovery-file (str (System/getProperty "user.home") "/.miniforge/dashboard.port")]
    (when (.exists (io/file discovery-file))
      (try
        (let [info (json/parse-string (slurp discovery-file) true)
              port (:port info)]
          (str "http://localhost:" port))
        (catch Exception _ nil)))))

;------------------------------------------------------------------------------ Layer 1
;; HTTP client helpers

(defn- http-get
  "Simple HTTP GET. Returns parsed JSON body or nil."
  [url]
  (try
    (let [conn (doto (.openConnection (java.net.URL. url))
                 (.setRequestMethod "GET")
                 (.setRequestProperty "Accept" "application/json")
                 (.setConnectTimeout 5000)
                 (.setReadTimeout 5000))
          status (.getResponseCode conn)]
      (when (= 200 status)
        (json/parse-string (slurp (.getInputStream conn)) true)))
    (catch Exception e
      (println (display/style (str "Error: " (ex-message e)) :foreground :red))
      nil)))

(defn- http-post
  "Simple HTTP POST with JSON body. Returns parsed JSON body or nil."
  [url body]
  (try
    (let [conn (doto (.openConnection (java.net.URL. url))
                 (.setRequestMethod "POST")
                 (.setRequestProperty "Content-Type" "application/json")
                 (.setRequestProperty "Accept" "application/json")
                 (.setDoOutput true)
                 (.setConnectTimeout 5000)
                 (.setReadTimeout 5000))
          body-str (json/generate-string body)]
      (with-open [os (.getOutputStream conn)]
        (.write os (.getBytes body-str "UTF-8")))
      (let [status (.getResponseCode conn)]
        (when (<= 200 status 299)
          (json/parse-string (slurp (.getInputStream conn)) true))))
    (catch Exception e
      (println (display/style (str "Error: " (ex-message e)) :foreground :red))
      nil)))

(defn- with-dashboard
  "Execute f with discovered dashboard base URL. Prints error if not found."
  [f]
  (if-let [base-url (discover-dashboard-url)]
    (f base-url)
    (do
      (println (display/style "Dashboard not running." :foreground :red))
      (println "Start it with: miniforge web")
      (println "Then try again."))))

;------------------------------------------------------------------------------ Layer 2
;; CLI commands

(defn status-cmd
  "Show control plane agent summary."
  [_opts]
  (with-dashboard
    (fn [base-url]
      (when-let [summary (http-get (str base-url "/api/control-plane/summary"))]
        (println)
        (println (display/style "Agent Control Plane" :foreground :cyan :bold true))
        (println)
        (println (str "  Total Agents:      " (:total_agents summary 0)))
        (println (str "  Pending Decisions: " (:pending_decisions summary 0)))
        (println (str "  Need Attention:    " (:agents_needing_attention summary 0)))
        (println)
        (when-let [by-status (:agents_by_status summary)]
          (println (display/style "  By Status:" :bold true))
          (doseq [[status cnt] (sort-by key by-status)]
            (println (str "    " (format "%-14s" status) cnt))))
        (println))

      ;; Also list agents
      (when-let [result (http-get (str base-url "/api/control-plane/agents"))]
        (let [agents (:agents result)]
          (when (seq agents)
            (println (display/style "  Agents:" :bold true))
            (doseq [a agents]
              (let [status-color (case (keyword (get a :agent/status "unknown"))
                                   :running :blue
                                   :blocked :yellow
                                   :failed :red
                                   :unreachable :red
                                   :completed :green
                                   :white)]
                (println (str "    "
                              (display/style (format "%-12s" (get a :agent/status "?"))
                                             :foreground status-color)
                              " "
                              (get a :agent/name "Unnamed")
                              " [" (get a :agent/vendor "?") "]"))))
            (println)))))))

(defn decisions-cmd
  "Show pending decisions."
  [_opts]
  (with-dashboard
    (fn [base-url]
      (when-let [result (http-get (str base-url "/api/control-plane/decisions"))]
        (let [decisions (:decisions result)]
          (println)
          (if (empty? decisions)
            (println (display/style "No pending decisions. All agents are autonomous." :foreground :green))
            (do
              (println (display/style (str "Pending Decisions (" (count decisions) ")") :foreground :cyan :bold true))
              (println)
              (doseq [d decisions]
                (let [priority (get d :decision/priority "medium")
                      priority-color (case (keyword priority)
                                       :critical :red
                                       :high :yellow
                                       :medium :blue
                                       :low :white
                                       :white)]
                  (println (str "  " (display/style (format "[%-8s]" (str/upper-case (name priority)))
                                                     :foreground priority-color)
                                " " (get d :decision/summary "?")))
                  (when-let [ctx (get d :decision/context)]
                    (println (str "           " (display/style ctx :foreground :white))))
                  (when-let [opts (get d :decision/options)]
                    (println (str "           Options: " (str/join ", " opts))))
                  (println (str "           ID: " (get d :decision/id)))
                  (println)))))
          (println))))))

(defn resolve-cmd
  "Resolve a pending decision."
  [opts]
  (let [decision-id (:decision-id opts)
        resolution (:resolution opts)
        comment (:comment opts)]
    (if (or (nil? decision-id) (nil? resolution))
      (do
        (println "Usage: miniforge control-plane resolve <decision-id> <resolution>")
        (println "       --comment \"optional comment\""))
      (with-dashboard
        (fn [base-url]
          (let [result (http-post (str base-url "/api/control-plane/decisions/"
                                       decision-id "/resolve")
                                  (cond-> {:resolution resolution}
                                    comment (assoc :comment comment)))]
            (if result
              (println (display/style (str "Decision resolved: " resolution) :foreground :green))
              (println (display/style "Failed to resolve decision." :foreground :red)))))))))

(defn terminate-cmd
  "Terminate an agent."
  [opts]
  (let [agent-id (:agent-id opts)]
    (if (nil? agent-id)
      (println "Usage: miniforge control-plane terminate <agent-id>")
      (with-dashboard
        (fn [base-url]
          (let [result (http-post (str base-url "/api/control-plane/agents/"
                                       agent-id "/command")
                                  {:command "terminate"})]
            (if result
              (println (display/style "Agent terminated." :foreground :green))
              (println (display/style "Failed to terminate agent." :foreground :red)))))))))
