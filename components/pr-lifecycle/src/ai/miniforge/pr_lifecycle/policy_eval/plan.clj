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

(ns ai.miniforge.pr-lifecycle.policy-eval.plan
  "N13 §2.5 Comment Response Agent — Layer 1: Domain (pure planning).

   Decide what to do with a policy-eval comment without performing
   any I/O: should it be applied, escalated to the operator, or
   skipped (no payload)? Then partition a batch of comments into
   the corresponding buckets.

   Pure throughout. Depends only on Layer 0 (payload extraction)."
  (:require
   [ai.miniforge.pr-lifecycle.policy-eval.payload :as payload]
   [clojure.string :as str]))

(defn- multi-line?
  "True when the suggested-fix spans more than one line. v0 skips
   these because applying a multi-line replacement requires knowing
   the original span, which the comment doesn't carry directly."
  [suggested-fix]
  (and (string? suggested-fix)
       (str/includes? suggested-fix "\n")))

(defn- plan-decision
  "Factory: build a `classify-fix` result map. Single source of
   truth for the `{:action :reason :payload}` shape — avoids hand-
   constructing the same map at every cond branch in `classify-fix`."
  [action reason payload]
  {:action  action
   :reason  reason
   :payload payload})

(defn classify-fix
  "Pure: decide what to do with a single policy-eval comment.
   Returns `{:action :apply | :escalate | :skip
             :reason  <keyword>
             :payload <payload-map-or-nil>}` via `plan-decision`."
  [comment]
  (let [p (payload/extract-policy-payload comment)]
    (cond
      (nil? p)
      (plan-decision :skip :no-payload nil)

      (false? (:violation/auto-fixable? p))
      (plan-decision :escalate :violation/auto-fixable?-false p)

      (str/blank? (:violation/suggested-fix p))
      (plan-decision :escalate :no-suggested-fix p)

      (multi-line? (:violation/suggested-fix p))
      (plan-decision :escalate :policy-eval/multi-line-not-supported p)

      :else
      (plan-decision :apply :ok p))))

(defn plan-fixes
  "Pure: walk a vector of comments, classify each, and partition into
   `{:to-apply [...] :to-escalate [...] :to-skip [...]}`. Each entry
   carries the original comment + its classification."
  [comments]
  (reduce
   (fn [acc comment]
     (let [c (classify-fix comment)
           bucket (case (:action c)
                    :apply    :to-apply
                    :escalate :to-escalate
                    :skip     :to-skip)]
       (update acc bucket conj
               {:comment comment
                :payload (:payload c)
                :reason  (:reason c)})))
   {:to-apply [] :to-escalate [] :to-skip []}
   comments))
