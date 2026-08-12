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
(ns ai.miniforge.buzzword-bingo.messages
  "Message catalog for this component, on every host.

   Same `{placeholder}` convention and `t` signature as the shared
   messages component, but the catalog is inlined at compile time
   rather than read from the classpath, because this component also
   compiles to a Node script where no classpath exists."
  (:require
   [clojure.string :as str]
   #?(:clj [ai.miniforge.buzzword-bingo.inline :as inline]))
  #?(:cljs (:require-macros [ai.miniforge.buzzword-bingo.inline :as inline])))

;------------------------------------------------------------------------------ Layer 0

;; Catalog
;; The macro needs a literal path, so it cannot come from a named constant.
(def ^{:stratum 0} ^:private catalog
  (get (inline/edn-resource "config/buzzword-bingo/messages/en-US.edn")
       :buzzword-bingo/messages))

(defn- ^{:stratum 0} substitute [template params]
  (reduce-kv (fn [s placeholder value]
               (str/replace s (str "{" (name placeholder) "}") (str value)))
             template
             params))

;------------------------------------------------------------------------------ Layer 1

;; Lookup
(defn ^{:stratum 1} t
  "Look up the message `k`, substituting `params` into `{placeholder}`
   tokens. An absent key renders as its own name rather than throwing,
   so a missing string degrades a report instead of failing it."
  ([k] (t k nil))
  ([k params]
   (let [template (get catalog k (name k))]
     (if (seq params)
       (substitute template params)
       template))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} counted
  "Look up the singular or plural form of `base` for `n`.

   Takes the two keys rather than deriving them, so a grep for a
   message key finds its use site."
  [one-key many-key n params]
  (t (if (= 1 n) one-key many-key) params))

(comment
  (t :report/heading)
  (t :report/score-line {:value 62.02})
  (counted :report/count-one :report/count-many 1 {:count 1}))
