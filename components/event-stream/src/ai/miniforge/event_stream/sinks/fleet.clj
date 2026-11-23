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
(ns ai.miniforge.event-stream.sinks.fleet
  "The fleet sink: batches events and POSTs each batch to fleet command
   over HTTP."
  (:require
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.logging.interface :as logging]
   [cheshire.core :as json]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.net.http HttpClient HttpResponse$BodyHandlers]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private default-fleet-sink-batch-size
  "Events buffered before the fleet HTTP sink flushes a batch. 10 keeps
   request overhead low without holding events long enough to lose many on a
   crash between flushes."
  10)

(def ^{:stratum 0} ^:private default-fleet-sink-flush-interval-ms
  "Maximum time the fleet HTTP sink holds a partial batch before flushing it
   regardless of size — bounds delivery latency for low event rates. 5s."
  5000)

(def ^{:stratum 0} ^:private default-fleet-sink-timeout-ms
  "HTTP request timeout for a fleet-sink batch POST. 10s — generous for a
   batched upload over a slow link, short enough that a hung endpoint doesn't
   stall the flush loop indefinitely."
  10000)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fleet-sink
  "Create a fleet sink that sends events to fleet command via HTTP.

   Arguments:
     opts - Map with required:
       :url - Fleet command URL (e.g., https://fleet.miniforge.ai)
       :api-key - API key for authentication
     Optional:
       :batch-size - Number of events to batch (default 10)
       :flush-interval-ms - Max time before flushing (default 5000)
       :timeout-ms - HTTP timeout (default 10000)
       :http-client - java.net.http.HttpClient instance (default: HttpClient/newHttpClient)

   Returns: Sink function (fn [event] -> nil)"
  [opts]
  ;; Missing-config throws are PROGRAMMER ERROR guards per standards
  ;; rule 005 §programmer-error-guards: IllegalArgumentException is the
  ;; rule-prescribed shape (not throw-anomaly! — that's for boundary
  ;; namespaces, and `event-stream.sinks` is not one). The constructor
  ;; has no return-value channel to surface an anomaly map without
  ;; breaking caller expectations of a callable sink.
  (let [url         (or (:url opts)
                        (throw (IllegalArgumentException.
                                 (messages/t :fleet-sink.system/missing-url))))
        api-key          (or (:api-key opts)
                             (throw (IllegalArgumentException.
                                      (messages/t :fleet-sink.system/missing-api-key))))
        batch-size       (:batch-size opts default-fleet-sink-batch-size)
        flush-interval-ms (:flush-interval-ms opts default-fleet-sink-flush-interval-ms)
        timeout-ms       (:timeout-ms opts default-fleet-sink-timeout-ms)
        http-client      (or (:http-client opts) (HttpClient/newHttpClient))
        batch-atom       (atom [])
        last-flush-atom  (atom (System/currentTimeMillis))]

    (letfn [(flush-batch! []
              ;; Atomically drain the batch so concurrent senders don't race
              ;; on @batch-atom + reset!. swap-vals! returns [prev new] —
              ;; first prev = the snapshot we own. Events appended while
              ;; the HTTP call is in flight go into the FRESH atom value
              ;; and are picked up by the next flush.
              (let [events (first (swap-vals! batch-atom (constantly [])))]
                (when (seq events)
                  ;; try+ per standards rule 211 — boundary catch around
                  ;; Java HttpClient.send. The component's other plain
                  ;; `try` sites (file write, mkdirs, etc.) predate this
                  ;; rule and are migrated opportunistically (211 §migration).
                  (try+
                    (let [body     (json/generate-string {:events events})
                          request  (logging/build-json-post (str url "/events") body api-key timeout-ms)
                          response (.send http-client request (HttpResponse$BodyHandlers/discarding))]
                      (when (>= (.statusCode response) 400)
                        (binding [*out* *err*]
                          (println (messages/t :fleet-sink.system/http-error
                                               {:url    (str url "/events")
                                                :status (.statusCode response)})))))
                    (catch InterruptedException e
                      ;; Preserve interrupt semantics for callers.
                      (.interrupt (Thread/currentThread))
                      (binding [*out* *err*]
                        (println (messages/t :fleet-sink.system/interrupted
                                             {:error (ex-message e)}))))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (messages/t :fleet-sink.system/flush-error
                                             {:error (ex-message e)}))))
                    (finally
                      (reset! last-flush-atom (System/currentTimeMillis)))))))]

      (fn [event]
        (swap! batch-atom conj event)
        (let [now (System/currentTimeMillis)
              time-since-flush (- now @last-flush-atom)
              batch-full? (>= (count @batch-atom) batch-size)
              time-exceeded? (>= time-since-flush flush-interval-ms)]
          (when (or batch-full? time-exceeded?)
            (flush-batch!)))))))
