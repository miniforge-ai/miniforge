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
(ns ai.miniforge.webhook-intake.listener
  "The intake's HTTP listener. Binds 127.0.0.1 unless told otherwise, and
   refuses to start without a webhook secret, so no bind address, public
   or not, accepts an unsigned delivery. GitHub cannot send a custom token
   header; the signature is the token."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.webhook-intake.deliveries :as deliveries]
   [ai.miniforge.webhook-intake.handler :as handler]
   [ai.miniforge.webhook-intake.messages :as messages]
   [clojure.string :as str]
   [org.httpkit.server :as http]
   [slingshot.slingshot :refer [throw+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-host
  "Bind address when none is given: loopback, reached through a tunnel
   or reverse proxy."
  "127.0.0.1")

(def ^{:stratum 0} stop-timeout-ms
  "How long stop waits for requests in flight to finish (5 s), so a
   delivery being handled is not cut off when the trigger is stopped."
  5000)

(def ^{:stratum 0} max-body-bytes
  "Largest request body accepted (5 MiB; http-kit answers 413 above it).
   GitHub caps payloads at 25 MB, but the deliveries mapped here are a few
   KB."
  (* 5 1024 1024))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start!
  "Start the intake on `:host` (default loopback) and `:port` (0 picks a
   free one), publishing to `:stream`. Throws an :invalid-input anomaly
   when `:secret` is blank. Returns {:host :port :stop-fn}."
  [{:keys [host port secret stream] :or {host default-host port 0}}]
  (when (str/blank? secret)
    (let [refusal (anomaly/anomaly :invalid-input (messages/system-t :intake/secret-required) {})]
      (throw+ refusal (:anomaly/message refusal))))
  (let [intake {:secret secret :stream stream :seen (deliveries/new-seen)}
        server (http/run-server (handler/create-handler intake)
                                {:ip host :port port :max-body max-body-bytes
                                 :legacy-return-value? false})]
    {:host host
     :port (http/server-port server)
     :stop-fn #(deref (http/server-stop! server {:timeout stop-timeout-ms})
                      (* 2 stop-timeout-ms) nil)}))
