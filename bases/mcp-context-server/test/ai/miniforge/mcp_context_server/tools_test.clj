;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.

(ns ai.miniforge.mcp-context-server.tools-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.mcp-context-server.tools :as tools]))

;; -------------------------------------------------------------------------- Layer 0
;; Helpers

(def ^:private anthropic-property-key-pattern
  "Anthropic's tool-schema validator pattern. Property keys outside
   this range cause a 400 response on every agent call."
  #"^[a-zA-Z0-9_.-]{1,64}$")

(defn- collect-property-keys
  "Walk a JSON schema map and collect every property key found under
   any nested `:properties` map. Returns a seq of [tool-name path key]
   triples to make assertion failures easy to read."
  [tool-name schema]
  (letfn [(walk [path node]
            (cond
              (map? node)
              (concat
                (when-let [props (get node :properties)]
                  (mapcat (fn [[k v]]
                            (cons [tool-name path k]
                                  (walk (conj path k) v)))
                          props))
                (mapcat (fn [[k v]]
                          (when (not= k :properties)
                            (walk (conj path k) v)))
                        node))

              (sequential? node)
              (mapcat #(walk path %) node)

              :else nil))]
    (walk [] schema)))

;; -------------------------------------------------------------------------- Layer 1
;; apply-param-aliases

(deftest apply-param-aliases-rewrites-listed-keys
  (testing "keys listed in aliases get renamed; others pass through"
    (let [aliases {"code_summary"       :code/summary
                   "code_tests_needed"  :code/tests-needed?}
          result (tools/apply-param-aliases
                   {"code_summary"      "did stuff"
                    "code_tests_needed" true
                    "passthrough"       42}
                   aliases)]
      (is (= "did stuff" (:code/summary result)))
      (is (= true (:code/tests-needed? result)))
      (is (= 42 (get result "passthrough"))
          "unaliased keys retain their original wire form"))))

(deftest apply-param-aliases-empty-is-identity
  (testing "nil/empty alias maps return the argument unchanged"
    (let [args {"a" 1 "b" 2}]
      (is (identical? args (tools/apply-param-aliases args nil)))
      (is (identical? args (tools/apply-param-aliases args {}))))))

;; -------------------------------------------------------------------------- Layer 2
;; tool-registry regression — every property key must satisfy Anthropic's pattern

(deftest registered-tool-schemas-match-anthropic-property-key-pattern
  (testing "every property key under any :inputSchema satisfies ^[a-zA-Z0-9_.-]{1,64}$"
    (doseq [[tool-name tool-config] (tools/tool-registry)]
      (let [schema (get-in tool-config [:tool-def :inputSchema])
            offenders (->> (collect-property-keys tool-name schema)
                           (remove (fn [[_ _ k]]
                                     (and (string? k)
                                          (re-matches anthropic-property-key-pattern k)))))]
        (is (empty? offenders)
            (str "Tool " (pr-str tool-name)
                 " has property keys Anthropic rejects: "
                 (str/join ", "
                           (map (fn [[_ path k]]
                                  (str (pr-str k) " at " (vec path)))
                                offenders))))))))

(deftest submit-tool-alias-map-covers-every-declared-property
  (testing "every input_schema property of the submit tool has a :param-aliases entry"
    (let [submit (get (tools/tool-registry) "submit")
          declared (set (keys (get-in submit [:tool-def :inputSchema :properties])))
          aliased  (set (keys (get submit :param-aliases {})))]
      (is (= declared aliased)
          (str "submit tool declares properties without aliases: "
               (set/difference declared aliased))))))
