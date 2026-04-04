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

(ns ai.miniforge.cli.web.response
  "HTTP response constructors."
  (:require
   [ai.miniforge.response.interface :as anomaly]))

(defn html [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str body)})

(defn not-found [msg]
  {:status 404 :body msg})

(defn bad-request [msg]
  {:status 400 :body msg})

(defn from-anomaly
  "Create an HTTP response from a canonical anomaly map.
   Uses the boundary translator — only call at HTTP edges."
  [anomaly-map]
  (anomaly/anomaly->http-response anomaly-map))

(defn sse-headers []
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"
             "Access-Control-Allow-Origin" "*"}})
