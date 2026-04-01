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
  "Provision phase interceptor.

   Executes infrastructure provisioning via Pulumi:
   1. pulumi preview --json → captures structured preview
   2. Policy gate checks preview for destructive/unsafe operations
   3. pulumi up --yes → applies approved changes
   4. Captures stack outputs as phase result

   Agent: none (CLI tool execution, no LLM)
   Default gates: [:policy-pack :provision-validated]"
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.deploy.evidence :as evidence]
            [ai.miniforge.phase.deploy.shell :as shell]
            [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults — overridable via workflow EDN."
  {:gates  [:policy-pack :provision-validated]
   :budget {:tokens 0 :iterations 3 :time-seconds 900}
   :agent  nil})

(registry/register-phase-defaults! :provision default-config)

;------------------------------------------------------------------------------ Layer 1
;; Preview analysis + enter-provision helpers

(defn- analyze-preview
  "Extract summary metrics from Pulumi preview JSON output.

   Returns:
     {:creates int :updates int :deletes int :sames int
      :resources [{:type :name :action}...]}"
  [preview-json]
  (if-let [steps (or (:steps preview-json) (:Steps preview-json))]
    (let [actions (map #(or (:op %) (:Op %)) steps)
          counts  (frequencies actions)]
      {:creates   (get counts "create" 0)
       :updates   (get counts "update" 0)
       :deletes   (get counts "delete" 0)
       :sames     (get counts "same" 0)
       :resources (mapv (fn [step]
                          {:type   (or (:type step) (get-in step [:newState :type]))
                           :name   (or (:urn step) (get-in step [:newState :urn]))
                           :action (or (:op step) (:Op step))})
                        steps)})
    {:creates 0 :updates 0 :deletes 0 :sames 0 :resources []}))

;------------------------------------------------------------------------------ Layer 2
;; Phase interceptors + registration

(defn enter-provision
  "Execute provisioning: preview → (gate check) → apply → capture outputs.

   The :enter function runs the preview and stores it as the phase artifact.
   After :enter returns, execution.clj checks gates (including :policy-pack)
   against the artifact. If gates pass, the leave function applies.

   For provision, we split into two sub-operations:
   - :enter does preview + stores artifact for gate checking
   - If gates pass, we need to apply. Since gates run between enter and leave,
     we store the 'needs apply' flag and do the apply in a post-gate callback.

   Architecture note: The current phase model runs enter → gates → leave.
   Apply must happen after gates pass but before leave. We handle this by
   doing both preview AND apply in enter, with the policy-pack gate checking
   the preview artifact mid-execution. The gate system checks after :enter
   returns, but we need apply to happen only if gates pass.

   Solution: enter does preview only. If gates pass, the workflow will proceed
   to the next phase. We use a two-phase approach: provision-preview and
   provision-apply as separate steps, OR we do the apply in enter after preview
   and let the gate check happen on the preview artifact.

   Chosen approach: Enter does preview. Stores preview as artifact for gate.
   If gates pass, the 'leave' triggers apply. If gates fail, leave does NOT apply.
   This aligns with the interceptor model: enter produces, gates validate, leave finalizes."
  [ctx]
  (let [config      (registry/merge-with-defaults (get-in ctx [:phase-config]) :provision)
        start-time  (System/currentTimeMillis)
        logger      (or (get-in ctx [:execution/logger])
                        (log/create-logger {:min-level :info :output :human}))
        ;; Extract provision-specific config from workflow input
        input       (get-in ctx [:execution/input])
        stack-dir   (or (:stack-dir input) (:stack-dir config))
        stack       (or (:stack input) (:stack config) "dev")
        gcp-project (or (:gcp-project input) (:gcp-project config))
        env         (cond-> {}
                      gcp-project (assoc "GOOGLE_PROJECT" gcp-project))]

    (log/info logger :provision :provision/preview-starting
              {:data {:stack-dir stack-dir :stack stack}})

    ;; Step 1: Run Pulumi preview
    (let [preview-result (shell/pulumi-preview! stack-dir :stack stack :env env)]
      (if-not (:success? preview-result)
        ;; Preview failed — surface error
        (let [error-type (shell/classify-error preview-result)]
          (log/error logger :provision :provision/preview-failed
                     {:data {:stderr (:stderr preview-result)
                             :error-type error-type}})
          (-> ctx
              (assoc-in [:phase :name] :provision)
              (assoc-in [:phase :status] :failed)
              (assoc-in [:phase :started-at] start-time)
              (assoc-in [:phase :result]
                        {:status     :error
                         :error      (:stderr preview-result)
                         :error-type error-type
                         :command    (:command preview-result)
                         :metrics    {:duration-ms (:duration-ms preview-result)}})))

        ;; Preview succeeded — analyze and store as artifact for gate checking
        (let [preview-data    (get preview-result :parsed {})
              analysis        (analyze-preview preview-data)
              preview-evidence (evidence/create-evidence
                                :evidence/pulumi-preview
                                preview-data
                                {:stack stack :stack-dir stack-dir
                                 :analysis analysis})]
          (log/info logger :provision :provision/preview-complete
                    {:data {:creates (:creates analysis)
                            :updates (:updates analysis)
                            :deletes (:deletes analysis)}})

          (-> ctx
              (assoc-in [:phase :name] :provision)
              (assoc-in [:phase :gates] (:gates config))
              (assoc-in [:phase :budget] (:budget config))
              (assoc-in [:phase :started-at] start-time)
              (assoc-in [:phase :status] :pending-gates)
              ;; Store the preview as the artifact that gates will check
              (assoc-in [:phase :result]
                        {:status   :preview-complete
                         :output   preview-data
                         :artifact {:content  preview-data
                                    :type     :pulumi-preview
                                    :analysis analysis}
                         :metrics  {:duration-ms (:duration-ms preview-result)
                                    :creates     (:creates analysis)
                                    :updates     (:updates analysis)
                                    :deletes     (:deletes analysis)}})
              ;; Store evidence
              (evidence/add-evidence-to-ctx preview-evidence)
              ;; Store config for leave phase to use for apply
              (assoc-in [:phase :provision-config]
                        {:stack-dir stack-dir :stack stack :env env})))))))

(defn leave-provision
  "After gates pass: execute pulumi up and capture outputs.

   Only applies if :enter succeeded and gates passed."
  [ctx]
  (let [phase-status (get-in ctx [:phase :status])
        logger       (or (get-in ctx [:execution/logger])
                         (log/create-logger {:min-level :info :output :human}))]

    ;; Only apply if preview succeeded and gates passed
    (if (or (= :failed phase-status) (= :error (get-in ctx [:phase :result :status])))
      ;; Already failed — pass through
      (-> ctx
          (assoc-in [:phase :status] :failed))

      ;; Gates passed — apply
      (let [config    (get-in ctx [:phase :provision-config])
            stack-dir (:stack-dir config)
            stack     (:stack config)
            env       (:env config)]

        (log/info logger :provision :provision/apply-starting
                  {:data {:stack-dir stack-dir :stack stack}})

        (let [apply-result (shell/pulumi-up! stack-dir :stack stack :env env)]
          (if-not (:success? apply-result)
            ;; Apply failed
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

            ;; Apply succeeded — get outputs
            (let [outputs-result (shell/pulumi-outputs! stack-dir :stack stack)
                  outputs        (get outputs-result :parsed {})
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
  (let [logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :error :output :human}))]
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
  ;; Test provision phase against a real Pulumi project
  ;; (enter-provision {:execution/input {:stack-dir "/path/to/project" :stack "dev"}})

  :leave-this-here)
