;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.messages
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resource-path "config/web-dashboard/messages/en-US.edn")
(def ^:private section-key :web-dashboard/messages)

(defn- load-catalog []
  (when-let [res (io/resource resource-path)]
    (get (edn/read-string (slurp res)) section-key {})))

(def ^:private catalog (delay (load-catalog)))

(defn t
  ([k] (t k {}))
  ([k params]
   (let [template (get @catalog k (name k))]
     (reduce-kv (fn [s pk pv]
                  (str/replace s (str "{" (name pk) "}") (str pv)))
                template
                params))))
