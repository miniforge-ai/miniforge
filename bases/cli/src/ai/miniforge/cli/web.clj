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

(ns ai.miniforge.cli.web
  "Fleet dashboard web server."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [org.httpkit.server :as http]
   [ai.miniforge.cli.web.response :as response]
   [ai.miniforge.cli.web.handlers :as handlers]
   [ai.miniforge.cli.web.sse :as sse]))

(def ^:dynamic *port* 8787)
(def ^:private server-atom (atom nil))

(defn parse-repos-from-config []
  (-> (str (System/getProperty "user.home") "/.miniforge/config.edn")
      java.io.File.
      (as-> f (when (.exists f) (slurp f)))
      (some-> edn/read-string (get-in [:fleet :repos]))
      (or [])))

(defn handler [{:keys [uri request-method] :as req}]
  (let [repos (parse-repos-from-config)]
    (cond
      (and (= uri "/") (= request-method :get))
      (handlers/index repos)

      (and (= uri "/api/refresh") (= request-method :get))
      (handlers/refresh repos)

      (and (= uri "/api/status") (= request-method :get))
      (handlers/status repos)

      (and (= uri "/api/workflows") (= request-method :get))
      (handlers/workflows repos)

      (and (= uri "/api/batch-approve") (= request-method :post))
      (handlers/batch-approve repos)

      (and (re-matches #"/api/workflows/[^/]+/stream" uri) (= request-method :get))
      (handlers/workflow-stream uri req)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/approve") (= request-method :post))
      (handlers/approve uri)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/reject") (= request-method :post))
      (handlers/reject uri req)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/chat") (= request-method :post))
      (handlers/chat uri req)

      (and (str/starts-with? uri "/api/pr/") (str/ends-with? uri "/summary") (= request-method :post))
      (handlers/summary uri)

      (and (str/starts-with? uri "/api/pr/") (= request-method :get))
      (handlers/pr-detail uri)

      :else
      (response/not-found "Not found"))))

(def register-workflow-stream! sse/register!)
(def unregister-workflow-stream! sse/unregister!)
(def get-workflow-stream sse/get-stream)

(defn stop-server! []
  (when-let [server @server-atom]
    (server)
    (reset! server-atom nil)
    (println "Server stopped.")))

(defn start-server! [& {:keys [port] :or {port *port*}}]
  (when @server-atom (stop-server!))
  (println (str "Starting fleet dashboard at http://localhost:" port))
  (reset! server-atom (http/run-server handler {:port port}))
  (println "Dashboard ready. Press Ctrl+C to stop."))

(comment
  (start-server! :port 8787)
  (stop-server!)
  :end)
