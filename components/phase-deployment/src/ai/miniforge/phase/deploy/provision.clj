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

(ns ai.miniforge.phase.deploy.provision
  "Provision phase interceptor."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.deploy.defaults :as defaults]
            [ai.miniforge.phase.deploy.evidence :as evidence]
            [ai.miniforge.phase.deploy.messages :as msg]
            [ai.miniforge.phase.deploy.shell :as shell]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults + schemas

(def default-config
  "Provision phase defaults loaded from EDN."
  (defaults/phase-defaults :provision))

(def ProvisionRunConfig
  [:map
   [:phase-config map?]
   [:stack-dir :string]
   [:stack :string]
   [:env [:map-of :string :string]]
   [:gcp-project {:optional true} [:maybe :string]]])

(def PreviewAnalysis
  [:map
   [:creates nat-int?]
   [:updates nat-int?]
   [:deletes nat-int?]
   [:sames nat-int?]
   [:resources
    [:vector
     [:map
      [:type {:optional true} [:maybe :string]]
      [:name {:optional true} [:maybe :string]]
      [:action {:optional true} [:maybe :string]]]]]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

;------------------------------------------------------------------------------ Layer 1
;; Shared helpers

(defn- get-logger
  "Resolve logger from ctx, creating a default if absent."
  [ctx]
  (or (get-in ctx [:execution/logger])
      (log/create-logger {:min-level :info :output :human})))

(defn- failed-enter
  "Build a :failed phase context for enter-time failures."
  [ctx start-time result-map]
  (-> ctx
      (assoc-in [:phase :name] :provision)
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :result] result-map)))

(defn- merged-phase-config
  [ctx phase-kw]
  (registry/merge-with-defaults
   (assoc (or (get-in ctx [:phase-config]) {}) :phase phase-kw)))

(defn- resolve-provision-config
  [ctx]
  (let [phase-config (merged-phase-config ctx :provision)
        input        (or (get-in ctx [:execution/input]) {})
        gcp-project  (or (get input :gcp-project)
                         (get phase-config :gcp-project))]
    (validate!
     ProvisionRunConfig
     (cond-> {:phase-config phase-config
              :stack-dir    (or (get input :stack-dir)
                                (get phase-config :stack-dir))
              :stack        (get input :stack
                                 (get phase-config :stack "dev"))
              :env          (cond-> {}
                              gcp-project (assoc "GOOGLE_PROJECT" gcp-project))}
       true (assoc :gcp-project gcp-project)))))

(defn analyze-preview
  "Extract summary metrics from Pulumi preview JSON output."
  [preview-json]
  (let [steps   (or (:steps preview-json) (:Steps preview-json) [])
        actions (map #(or (:op %) (:Op %)) steps)
        counts  (frequencies actions)]
    (validate!
     PreviewAnalysis
     {:creates   (get counts "create" 0)
      :updates   (get counts "update" 0)
      :deletes   (get counts "delete" 0)
      :sames     (get counts "same" 0)
      :resources (mapv (fn [step]
                         {:type   (or (:type step) (get-in step [:newState :type]))
                          :name   (or (:urn step) (get-in step [:newState :urn]))
                          :action (or (:op step) (:Op step))})
                       steps)})))

(defn- preview-result
  [preview-data analysis preview-command]
  {:status   :preview-complete
   :output   preview-data
   :artifact {:content  preview-data
              :type     :pulumi-preview
              :analysis analysis}
   :command  preview-command
   :metrics  {:duration-ms (:duration-ms preview-command)
              :creates     (:creates analysis)
              :updates     (:updates analysis)
              :deletes     (:deletes analysis)}})

(defn- store-provision-preview
  [ctx start-time config preview-command]
  (let [preview-data     (get preview-command :parsed {})
        analysis         (analyze-preview preview-data)
        preview-evidence (evidence/create-evidence
                          :evidence/pulumi-preview
                          preview-data
                          {:stack (:stack config)
                           :stack-dir (:stack-dir config)
                           :analysis analysis})]
    (log/info (get-logger ctx) :provision :provision/preview-complete
              {:data {:creates (:creates analysis)
                      :updates (:updates analysis)
                      :deletes (:deletes analysis)}})
    (-> ctx
        (assoc-in [:phase :name] :provision)
        (assoc-in [:phase :gates] (get-in config [:phase-config :gates]))
        (assoc-in [:phase :budget] (get-in config [:phase-config :budget]))
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :pending-gates)
        (assoc-in [:phase :result] (preview-result preview-data
                                                   analysis
                                                   preview-command))
        (evidence/add-evidence-to-ctx preview-evidence)
        (assoc-in [:phase :provision-config]
                  (select-keys config [:stack-dir :stack :env])))))

(defn- store-provision-failure
  [ctx start-time logger preview-result]
  (let [error-type (shell/classify-error preview-result)]
    (log/error logger :provision :provision/preview-failed
               {:data {:stderr (:stderr preview-result)
                       :error-type error-type}})
    (failed-enter ctx start-time
                  {:status     :error
                   :error      (:stderr preview-result)
                   :error-type error-type
                   :command    (:command preview-result)
                   :metrics    {:duration-ms (:duration-ms preview-result)}})))

(defn- invalid-config-result
  [ctx start-time ex]
  (failed-enter ctx start-time
                {:status :error
                 :error  (msg/t :provision/invalid-config
                                {:error (ex-message ex)})}))

;------------------------------------------------------------------------------ Layer 2
;; Phase interceptors + registration

(defn enter-provision
  "Execute provisioning preview and capture the artifact used by gates."
  [ctx]
  (let [start-time (System/currentTimeMillis)
        logger     (get-logger ctx)]
    (try
      (let [config         (resolve-provision-config ctx)
            stack-dir      (:stack-dir config)
            stack          (:stack config)
            preview-result (do
                             (log/info logger :provision :provision/preview-starting
                                       {:data {:stack-dir stack-dir :stack stack}})
                             (shell/pulumi-preview! stack-dir
                                                    :stack stack
                                                    :env (:env config)))]
        (if (schema/failed? preview-result)
          (store-provision-failure ctx start-time logger preview-result)
          (store-provision-preview ctx start-time config preview-result)))
      (catch clojure.lang.ExceptionInfo ex
        (invalid-config-result ctx start-time ex)))))

(defn leave-provision
  "After gates pass: execute pulumi up and capture outputs.

   Only applies if :enter succeeded and gates passed."
  [ctx]
  (let [phase-status (get-in ctx [:phase :status])
        logger       (get-logger ctx)]
    (if (or (= :failed phase-status)
            (= :error (get-in ctx [:phase :result :status])))
      (assoc-in ctx [:phase :status] :failed)
      (let [config    (get-in ctx [:phase :provision-config])
            stack-dir (:stack-dir config)
            stack     (:stack config)
            env       (:env config)]
        (log/info logger :provision :provision/apply-starting
                  {:data {:stack-dir stack-dir :stack stack}})
        (let [apply-result (shell/pulumi-up! stack-dir :stack stack :env env)]
          (if (schema/failed? apply-result)
            (do
              (log/error logger :provision :provision/apply-failed
                         {:data {:stderr (:stderr apply-result)}})
              (-> ctx
                  (assoc-in [:phase :status] :failed)
                  (update-in [:phase :result] merge
                             {:status  :apply-failed
                              :error   (:stderr apply-result)
                              :metrics (merge (get-in ctx [:phase :result :metrics])
                                              {:apply-duration-ms (:duration-ms apply-result)})})))
            (let [outputs-result   (shell/pulumi-outputs! stack-dir :stack stack)
                  outputs          (get outputs-result :parsed {})
                  outputs-evidence (evidence/create-evidence
                                    :evidence/pulumi-outputs
                                    outputs
                                    {:stack stack})]
              (log/info logger :provision :provision/apply-complete
                        {:data {:output-keys (vec (keys outputs))}})
              (-> ctx
                  (assoc-in [:phase :status] :completed)
                  (update-in [:phase :result] merge
                             {:status  :success
                              :outputs outputs
                              :metrics (merge (get-in ctx [:phase :result :metrics])
                                              {:apply-duration-ms (:duration-ms apply-result)})})
                  (evidence/add-evidence-to-ctx outputs-evidence)))))))))

(defn error-provision
  "Handle provision phase errors."
  [ctx ex]
  (let [logger (get-logger ctx)]
    (log/error logger :provision :provision/error
               {:data {:message (ex-message ex)
                       :data    (ex-data ex)}})
    (-> ctx
        (assoc-in [:phase :status] :failed)
        (update :execution/errors (fnil conj [])
                {:type    :provision-error
                 :phase   :provision
                 :message (ex-message ex)
                 :data    (ex-data ex)}))))

;; Registration

(defmethod registry/get-phase-interceptor :provision
  [_]
  {:name   :provision
   :enter  enter-provision
   :leave  leave-provision
   :error  error-provision
   :config default-config})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (enter-provision {:execution/input {:stack-dir "/path/to/project" :stack "dev"}})
  :leave-this-here)
