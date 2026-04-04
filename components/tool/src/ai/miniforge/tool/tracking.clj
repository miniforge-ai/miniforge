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

(ns ai.miniforge.tool.tracking
  "Tool invocation tracking helpers.")

;------------------------------------------------------------------------------ Layer 0
;; Invocation records

(defn instant-from-ms
  "Create an Instant from epoch millis."
  [millis]
  (java.time.Instant/ofEpochMilli millis))

(defn build-invocation
  "Build a tool invocation record from execution inputs and result."
  [tool-id params start-ms end-ms result]
  (let [duration (- end-ms start-ms)]
    (cond-> {:tool/id tool-id
             :tool/invoked-at (instant-from-ms start-ms)
             :tool/duration-ms (max 0 duration)
             :tool/args (or params {})}
      (contains? result :result)
      (assoc :tool/result (:result result))

      (contains? result :exit-code)
      (assoc :tool/exit-code (:exit-code result))

      (contains? result :error)
      (assoc :tool/error (:error result)))))

;------------------------------------------------------------------------------ Layer 1
;; Context helpers

(defn attach-invocation-tracking
  "Attach invocation tracking to a context map.
   Adds :tool/invocations (atom) and :tool/record-invocation when missing."
  [context]
  (let [existing (:tool/invocations context)]
    (if (and existing (:tool/record-invocation context))
      context
      (let [store (if (instance? clojure.lang.IAtom existing)
                    existing
                    (atom (vec (or existing []))))
            record-fn (fn [invocation]
                        (swap! store conj invocation))]
        (assoc context
               :tool/invocations store
               :tool/record-invocation record-fn)))))

(defn tool-invocations
  "Return tool invocations from a context map."
  [context]
  (let [store (:tool/invocations context)]
    (cond
      (instance? clojure.lang.IAtom store) @store
      (sequential? store) (vec store)
      :else [])))

(defn record-invocation
  "Record a tool invocation using context if tracking is configured."
  [context invocation]
  (let [record-fn (:tool/record-invocation context)
        store (:tool/invocations context)]
    (cond
      (fn? record-fn)
      (record-fn invocation)

      (instance? clojure.lang.IAtom store)
      (swap! store conj invocation)

      :else nil))
  invocation)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def ctx (attach-invocation-tracking {}))
  (record-invocation ctx
                     {:tool/id :tools/demo
                      :tool/invoked-at (java.time.Instant/now)
                      :tool/duration-ms 10
                      :tool/args {:x 1}
                      :tool/result :ok})
  (tool-invocations ctx)

  :leave-this-here)
