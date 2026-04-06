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

(ns ai.miniforge.mcp-context-server.tools
  "Tool definitions and handlers for MCP context server.

   Configuration-driven: tool definitions are loaded from
   `mcp-context-server/tool-registry.edn` on the classpath. Handler functions
   are registered separately via `register-handler!`, enabling extensibility
   without modifying the config file."
  (:require [ai.miniforge.mcp-context-server.messages :as msg]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Keyword validators — referenced by keyword in EDN config

(def validators
  "Keyword → predicate map for validation rules in the registry config.
   These are the only functions that bridge EDN config to runtime behavior."
  {:non-empty (fn [v] (and v (seq v)))
   :non-blank (fn [v] (and (string? v) (not (str/blank? v))))})

(defn resolve-validator
  "Resolve a keyword validator to its predicate function."
  [check-kw]
  (or (get validators check-kw)
      (throw (ex-info (str "Unknown validator: " check-kw)
                      {:validator check-kw
                       :available (keys validators)}))))

;------------------------------------------------------------------------------ Layer 0
;; Validation

(defn validate-required-params
  "Validate required params against rules. Throws ex-info with code -32602 on failure.
   Accepts keyword validators (from EDN config) or function validators."
  [required-params params]
  (doseq [[param-name {:keys [check msg]}] required-params]
    (let [check-fn (if (keyword? check) (resolve-validator check) check)
          v (get params param-name)]
      (when-not (check-fn v)
        (throw (ex-info msg {:code -32602}))))))

;------------------------------------------------------------------------------ Layer 0
;; Param extractors

(defn non-blank
  "Return string if non-blank, else nil."
  [s]
  (when-not (str/blank? s) s))

;------------------------------------------------------------------------------ Layer 1
;; Extensible handler registry

(defonce handler-registry*
  (atom {}))

(defn register-handler!
  "Register a direct handler function for a tool.
   handler-key is a keyword matching the :handler field in tool-registry.edn.
   handler-fn takes params map, returns {:content [{:type \"text\" :text ...}]}."
  [handler-key handler-fn]
  (swap! handler-registry* assoc handler-key handler-fn))

(defn resolve-handler
  "Resolve a handler keyword to its function."
  [handler-key]
  (or (get @handler-registry* handler-key)
      (throw (ex-info (msg/t :tool/no-handler {:key handler-key})
                      {:handler handler-key
                       :registered (keys @handler-registry*)}))))

;------------------------------------------------------------------------------ Layer 1
;; Configuration loading

(defn load-registry-config
  "Load tool registry from classpath EDN configuration."
  []
  (if-let [resource (io/resource "mcp-context-server/tool-registry.edn")]
    (edn/read-string (slurp resource))
    (throw (ex-info "Tool registry config not found on classpath"
                    {:resource "mcp-context-server/tool-registry.edn"}))))

(defonce registry-config* (atom nil))

(defn ensure-registry-loaded
  "Ensure the registry config is loaded (once)."
  []
  (when-not @registry-config*
    (reset! registry-config* (load-registry-config)))
  @registry-config*)

(defn tool-registry
  "Return the current tool registry (loads from config on first call)."
  []
  (ensure-registry-loaded))

(defn register-tool!
  "Register a new tool at runtime. Merges into the loaded registry.
   tool-name is a string, tool-config is a map with :tool-def, :required-params, :handler."
  [tool-name tool-config]
  (ensure-registry-loaded)
  (swap! registry-config* assoc tool-name tool-config))

;------------------------------------------------------------------------------ Layer 2
;; Tool definitions list (derived from registry)

(defn tool-definitions
  "MCP tool definitions derived from the registry."
  []
  (mapv :tool-def (vals (tool-registry))))

;------------------------------------------------------------------------------ Layer 2
;; Generic tool handler

(defn handle-tool-call
  "Handle an MCP tools/call request.

   All tools use :handler dispatch — they return {:content [...]} directly.

   Arguments:
   - tool-name — string name of the tool
   - arguments — map of tool arguments

   Returns {:content [{:type \"text\" :text message}]}
   Throws on unknown tool or validation failure."
  [tool-name arguments]
  (let [registry (tool-registry)
        {:keys [required-params handler]} (get registry tool-name)]
    (when-not handler
      (throw (ex-info (msg/t :tool/unknown {:tool-name tool-name}) {:code -32601})))
    (validate-required-params required-params arguments)
    ((-> handler resolve-handler) arguments)))
