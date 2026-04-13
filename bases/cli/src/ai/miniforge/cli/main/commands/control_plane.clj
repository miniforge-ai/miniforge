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
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Color mappings (loaded from resource catalog)

(defn- status->color [status]
  (get (messages/t :cp/status-colors) status :white))

(defn- priority->color [priority]
  (get (messages/t :cp/priority-colors) priority :white))

;------------------------------------------------------------------------------ Layer 0
;; Dashboard discovery

(defn- discover-dashboard-url
  "Read the dashboard port from discovery file.
   Returns base URL string or nil."
  []
  (let [discovery-file (str (config/miniforge-home) "/dashboard.port")]
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
      (println (display/style (messages/t :cp/dashboard-not-running) :foreground :red))
      (println (messages/t :cp/dashboard-start-hint))
      (println (messages/t :cp/dashboard-try-again)))))

;------------------------------------------------------------------------------ Layer 2
;; CLI commands

(defn status-cmd
  "Show control plane agent summary."
  [_opts]
  (with-dashboard
    (fn [base-url]
      (when-let [summary (http-get (str base-url "/api/control-plane/summary"))]
        (println)
        (println (display/style (messages/t :cp/status-header) :foreground :cyan :bold true))
        (println)
        (println (messages/t :cp/total-agents-label {:count (:total_agents summary 0)}))
        (println (messages/t :cp/pending-decisions-label {:count (:pending_decisions summary 0)}))
        (println (messages/t :cp/need-attention-label {:count (:agents_needing_attention summary 0)}))
        (println)
        (when-let [by-status (:agents_by_status summary)]
          (println (display/style (messages/t :cp/by-status-header) :bold true))
          (doseq [[status cnt] (sort-by key by-status)]
            (println (str "    " (format "%-14s" status) cnt))))
        (println))

      ;; Also list agents
      (when-let [result (http-get (str base-url "/api/control-plane/agents"))]
        (let [agents (:agents result)]
          (when (seq agents)
            (println (display/style (messages/t :cp/agents-header) :bold true))
            (let [fallback (messages/t :cp/unknown-fallback)]
              (doseq [a agents]
                (let [status-kw (keyword (get a :agent/status "unknown"))
                      color (status->color status-kw)
                      status-str (display/style (format "%-12s" (get a :agent/status fallback))
                                                :foreground color)]
                  (println (messages/t :cp/agent-line
                                       {:status status-str
                                        :name   (get a :agent/name (messages/t :cp/unnamed-agent))
                                        :vendor (get a :agent/vendor fallback)}))))))
            (println))))))

(defn decisions-cmd
  "Show pending decisions."
  [_opts]
  (with-dashboard
    (fn [base-url]
      (when-let [result (http-get (str base-url "/api/control-plane/decisions"))]
        (let [decisions (:decisions result)]
          (println)
          (if (empty? decisions)
            (println (display/style (messages/t :cp/no-decisions-msg) :foreground :green))
            (do
              (println (display/style (messages/t :cp/pending-decisions-header {:count (count decisions)})
                                      :foreground :cyan :bold true))
              (println)
              (let [fallback (messages/t :cp/unknown-fallback)]
                (doseq [d decisions]
                  (let [priority-kw (keyword (get d :decision/priority "medium"))
                        color (priority->color priority-kw)
                        badge (display/style (format "[%-8s]" (str/upper-case (name priority-kw)))
                                             :foreground color)]
                    (println (messages/t :cp/decision-line
                                         {:priority badge
                                          :summary  (get d :decision/summary fallback)}))
                    (when-let [ctx (get d :decision/context)]
                      (println (messages/t :cp/decision-context-line
                                           {:context (display/style ctx :foreground :white)})))
                    (when-let [opts (get d :decision/options)]
                      (println (messages/t :cp/decision-options-label
                                           {:options (str/join ", " opts)})))
                    (println (messages/t :cp/decision-id-label {:id (get d :decision/id)}))
                    (println))))))
          (println))))))

(defn resolve-cmd
  "Resolve a pending decision."
  [opts]
  (let [decision-id (:decision-id opts)
        resolution (:resolution opts)
        comment (:comment opts)]
    (if (or (nil? decision-id) (nil? resolution))
      (do
        (println (messages/t :cp/resolve-usage))
        (println (messages/t :cp/resolve-usage-comment)))
      (with-dashboard
        (fn [base-url]
          (let [result (http-post (str base-url "/api/control-plane/decisions/"
                                       decision-id "/resolve")
                                  (cond-> {:resolution resolution}
                                    comment (assoc :comment comment)))]
            (if result
              (println (display/style (messages/t :cp/resolve-success {:resolution resolution})
                                      :foreground :green))
              (println (display/style (messages/t :cp/resolve-failed) :foreground :red)))))))))

(defn terminate-cmd
  "Terminate an agent."
  [opts]
  (let [agent-id (:agent-id opts)]
    (if (nil? agent-id)
      (println (messages/t :cp/terminate-usage))
      (with-dashboard
        (fn [base-url]
          (let [result (http-post (str base-url "/api/control-plane/agents/"
                                       agent-id "/command")
                                  {:command "terminate"})]
            (if result
              (println (display/style (messages/t :cp/terminate-success) :foreground :green))
              (println (display/style (messages/t :cp/terminate-failed) :foreground :red)))))))))
