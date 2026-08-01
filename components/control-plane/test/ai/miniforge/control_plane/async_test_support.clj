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
(ns ai.miniforge.control-plane.async-test-support
  "Wait helpers shared by the orchestrator test namespaces.

   The orchestrator runs discovery, polling, and the heartbeat watchdog on
   background futures, so tests observe their effects from another thread.
   These helpers block on the effect itself rather than sleeping for a
   guessed interval, so a loaded CI runner makes them slower, not wrong."
  (:import
   (java.util.concurrent.locks LockSupport)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-wait-timeout-ms
  "Upper bound on any wait below. A deadlock guard, not a timing assumption:
   the helpers return as soon as their condition holds."
  2000)

(def ^{:stratum 0} ^:private nanos-per-ms
  "Nanoseconds in a millisecond."
  1000000)

(def ^{:stratum 0} ^:private poll-park-ms
  "How long `wait-until` parks between polls."
  1)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} wait-until-count
  "Block until `(count @items-atom) >= n` or `timeout-ms` elapses.
   Returns true if reached, false on timeout.

   The watch is installed BEFORE the current value is read. The reverse
   order drops a wakeup: an update landing between the read and the
   add-watch is seen by neither, so the wait times out with the condition
   already satisfied. `deliver` on a settled promise is a no-op, so the
   two paths racing each other is harmless."
  ([items-atom n] (wait-until-count items-atom n default-wait-timeout-ms))
  ([items-atom n timeout-ms]
   (let [done (promise)
         k    (gensym "wait-count-")]
     (add-watch items-atom k
                (fn [_ _ _ new-val]
                  (when (>= (count new-val) n)
                    (deliver done :reached))))
     (when (>= (count @items-atom) n)
       (deliver done :immediate))
     (let [result (deref done timeout-ms ::timeout)]
       (remove-watch items-atom k)
       (not= result ::timeout)))))

(defn ^{:stratum 1} wait-until
  "Block until `(pred)` returns truthy or `timeout-ms` elapses.
   Returns true if pred satisfied, false on timeout.

   Polls, so it carries the cost of a stale read for up to one park. Prefer
   `wait-until-count`, or a promise the code under test delivers to, when
   the condition is reachable as a watched value.

   Deadline is on `nanoTime`, not `currentTimeMillis`: the wall clock can
   step (NTP, a suspend/resume) and cut the wait short, which is the class
   of failure this namespace exists to remove."
  ([pred] (wait-until pred default-wait-timeout-ms))
  ([pred timeout-ms]
   (let [deadline (+ (System/nanoTime) (* timeout-ms nanos-per-ms))]
     (loop []
       (cond
         (pred) true
         ;; Subtract rather than compare: nanoTime's origin is arbitrary and
         ;; the difference stays correct if the counter wraps.
         (neg? (- deadline (System/nanoTime))) false
         :else (do (LockSupport/parkNanos (* poll-park-ms nanos-per-ms))
                   (recur)))))))
