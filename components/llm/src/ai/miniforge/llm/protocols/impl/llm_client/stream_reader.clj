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
(ns ai.miniforge.llm.protocols.impl.llm-client.stream-reader
  "Reading a streaming subprocess's stdout: the reader daemon, the line
   queue and its signals (line, EOF, anomaly, idle timeout), and polling
   it."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [ai.miniforge.llm.protocols.impl.llm-client.subprocess :as subprocess]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private eof-sentinel
  (Object.))

(defn ^{:stratum 0} open-stream-dump-writer
  []
  (when-let [dump-path (System/getenv "MF_STREAM_DUMP")]
    (java.io.PrintWriter.
     (java.io.FileWriter. dump-path true))))

(defn- ^{:stratum 0} stream-read-failure
  [ex]
  (anomaly/exception-anomaly :fault (or (ex-message ex) (str (type ex))) ex))

(defn- ^{:stratum 0} enqueue-stream-line!
  [line-queue line]
  (.put line-queue line))

(defn- ^{:stratum 0} record-stream-line!
  [out-lines dump-writer on-line last-line-at now line]
  (reset! last-line-at now)
  (swap! out-lines conj line)
  (when dump-writer
    (.println dump-writer line)
    (.flush dump-writer))
  (on-line line))

(defn- ^{:stratum 0} stream-idle-timeout
  [last-line-at line-timeout-ms out-lines now]
  (when (>= (- now @last-line-at) line-timeout-ms)
    {:type :stream-idle
     :message (str "No stream output for " line-timeout-ms "ms")
     :elapsed-ms (- now @last-line-at)
     :stats {:lines-read (count @out-lines)}}))

(defn- ^{:stratum 0} anomaly-signal?
  [line-or-signal]
  (anomaly/anomaly? line-or-signal))

(defn ^{:stratum 0} stop-stream-reader!
  "Release the reader daemon once the consumer has stopped polling.

   The close runs on its own daemon thread. `BufferedReader.close` takes
   the lock `readLine` holds while it blocks on the process pipe, so an
   inline close waits for the next line or EOF; a silent subprocess sends
   neither, and the lock wait ignores interrupts, so the calling thread
   would hang before it could kill the process. Callers kill the process
   tree on every abnormal exit; that ends the blocked read and lets the
   close through."
  [^Thread reader-thread out-reader]
  (subprocess/daemon-thread! "llm-stream-reader-close"
                  (fn []
                    (try+
                      (.close out-reader)
                      ;; JVM Errors stay fatal; anything else, ex-info
                      ;; included (`Object`), is a best-effort close.
                      (catch Error fatal
                        (throw fatal))
                      (catch Object _
                        nil))))
  (.interrupt reader-thread)
  (.join reader-thread subprocess/stream-reader-join-timeout-ms))

(defn- ^{:stratum 0} stream-poll-interval-ms
  []
  (settings/client-default [:stream :poll-interval-ms]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-stream-loop!
  [out-reader line-queue]
  (loop []
    (if-some [line (.readLine out-reader)]
      (do (enqueue-stream-line! line-queue line)
          (recur))
      (enqueue-stream-line! line-queue eof-sentinel))))

(defn- ^{:stratum 1} eof-signal?
  [line-or-signal]
  (identical? line-or-signal eof-sentinel))

(defn- ^{:stratum 1} timeout-signal
  [last-line-at line-timeout-ms out-lines]
  {:done? false
   :timeout (stream-idle-timeout last-line-at
                                 line-timeout-ms
                                 out-lines
                                 (System/currentTimeMillis))})

(defn ^{:stratum 1} stream-poll-signal
  [line-queue]
  (.poll line-queue
         (stream-poll-interval-ms)
         TimeUnit/MILLISECONDS))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start-stream-reader!
  "Start the daemon that drains `out-reader` into `line-queue`.

   Three exit shapes:
   1. EOF — `read-stream-loop!` enqueues the eof sentinel and returns; thread
      exits cleanly.
   2. Clean shutdown via `stop-stream-reader!` — the consumer
      (`process-stream-lines`) has stopped polling, so `stop-stream-reader!`
      closes the reader and calls `.interrupt`. `BufferedReader.readLine`
      is NOT interruptible, and the close cannot cut it short either (it
      waits for the lock `readLine` holds), so a reader blocked there
      unblocks when the process dies and the pipe reaches EOF. A reader
      blocked in `.put` on a full queue unblocks via the `.interrupt` and
      raises `InterruptedException`. Both cases land in the same silent
      catch: the consumer already left the queue; no anomaly to publish.
   3. Real read error — surface as a `stream-read-failure` anomaly so the
      consumer sees it. The inner try/catch around the anomaly enqueue
      guards against a sticky-interrupt race during cleanup (the thread's
      interrupt flag is still set from case 2, so `.put` here can throw IE
      too — swallow it; the consumer is already gone).

   Pre-fix, case 2 was caught by the generic `(catch Exception e ...)` which
   then called `enqueue-stream-line!` → `.put` → a second IE that bubbled out
   of the runnable to the JVM default exception handler and printed an ugly
   stack trace to stderr. The 2026-06-04 eliminate-requiring-resolve dogfood
   surfaced 18 of these IEs across 20 sub-tasks — pure log noise, zero
   workflow impact, but enough to look like a real bug in post-run triage."
  [out-reader line-queue]
  (subprocess/daemon-thread!
   "llm-stream-reader"
   (fn []
     (try
       (read-stream-loop! out-reader line-queue)
       (catch InterruptedException _
         ;; clean shutdown — consumer already exited; nothing to report
         nil)
       (catch Exception e
         (try
           (enqueue-stream-line! line-queue (stream-read-failure e))
           (catch InterruptedException _
             ;; the stop-stream-reader! interrupt raced this catch
             nil)))))))

(defn ^{:stratum 2} process-stream-signal
  [line-or-signal out-lines dump-writer on-line last-line-at line-timeout-ms]
  (cond
    (anomaly-signal? line-or-signal)
    {:done? true
     :anomaly line-or-signal}

    (eof-signal? line-or-signal)
    {:done? true}

    (some? line-or-signal)
    (do (record-stream-line! out-lines
                             dump-writer
                             on-line
                             last-line-at
                             (System/currentTimeMillis)
                             line-or-signal)
        {:done? false})

    :else
    (timeout-signal last-line-at line-timeout-ms out-lines)))
