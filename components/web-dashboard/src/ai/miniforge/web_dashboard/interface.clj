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

(ns ai.miniforge.web-dashboard.interface
  "Public API for the web dashboard component.

   Provides HTTP server with WebSocket for real-time workflow visualization."
  (:require
   [ai.miniforge.web-dashboard.server :as server]))

;------------------------------------------------------------------------------ Layer 0
;; Public API

(defn start!
  "Start the web dashboard server.

   Options:
   - :port - Port to listen on (default 8080)
   - :event-stream - Event stream atom for subscribing to workflow events

   Returns: server handle (use with stop!)

   Example:
     (def server (start! {:port 8080 :event-stream my-stream}))"
  [opts]
  (server/start-server! opts))

(defn stop!
  "Stop the web dashboard server.

   Arguments:
   - server - Server handle from start!

   Example:
     (stop! server)"
  [server]
  (server/stop-server! server))

(defn get-port
  "Get the port the server is listening on.

   Arguments:
   - server - Server handle from start!

   Returns: port number (integer)

   Example:
     (get-port server) ;=> 8080"
  [server]
  (server/get-server-port server))
