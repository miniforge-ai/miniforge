;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.manifest-credentials
  "Find plaintext credentials in rendered Kubernetes manifests by their
   structure, not their text. Detector for the deployment-safety pack's
   :deploy/no-secrets-in-manifests rule.

   Per YAML document:
   1. kind Secret with a literal `data` or `stringData` value is a finding,
      unless the rule's :detector-config sets :allow-secret-data? true.
   2. A container env entry whose name is a credential name and that has a
      literal `value` (not `valueFrom`) is a finding.
   3. A ConfigMap `data` key that is a credential name with a non-empty
      literal value is a finding; multiword values count.
   4. Annotations and labels (`metadata`) are never read.
   A literal value holding a PEM private key is a finding wherever rules 2
   and 3 look. Findings name where the value is, never the value.

   Content that is not YAML, or YAML documents that are not manifests (maps),
   falls back to the rule's line patterns (:detector-config :fallback-patterns)."
  (:require [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.policy-pack.interface :as policy-pack]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} credential-name?
  "True when a key or env var name ends in a credential term: DB_PASSWORD,
   apiKey, client-secret, AWS_SECRET_ACCESS_KEY. secretName, passwordRef and
   ACCESS_KEY_ID end in another word and are not credentials."
  [s]
  (let [terms #{["password"] ["passwd"] ["pwd"] ["pass"] ["passphrase"] ["secret"]
                ["token"] ["credential"] ["credentials"] ["apikey"]
                ["api" "key"] ["access" "key"] ["private" "key"] ["secret" "key"]}
        tokens (->> (str/split (str/replace (str s) #"([a-z0-9])([A-Z])" "$1 $2")
                               #"[^A-Za-z0-9]+")
                    (remove str/blank?)
                    (mapv str/lower-case))]
    (boolean (some #(= % (vec (take-last (count %) tokens))) terms))))

(defn ^{:stratum 0} literal-value?
  "A non-empty literal value. A reference to another env var, `$(NAME)` or
   `${NAME}`, is not a literal."
  [v]
  (and (some? v)
       (not (coll? v))
       (not (str/blank? (str v)))
       (not (re-matches #"\$[({][A-Za-z_][A-Za-z0-9_]*[)}]" (str/trim (str v))))))

(defn- ^{:stratum 0} private-key? [v]
  (and (string? v) (str/includes? v "-----BEGIN") (str/includes? v "PRIVATE KEY")))

(defn- ^{:stratum 0} env-entries
  "Every env entry in a document's pod specs, wherever the workload nests them.
   metadata (annotations and labels) is not walked."
  [document]
  (->> (tree-seq coll? seq (dissoc document "metadata"))
       (filter map?)
       (keep #(get % "env"))
       (filter sequential?)
       (mapcat identity)
       (filter map?)))

(defn- ^{:stratum 0} fallback-match?
  "For content that is not YAML: does any line match a fallback pattern?"
  [patterns content]
  (let [compiled (map re-pattern patterns)]
    (boolean (some (fn [line] (some #(re-find % line) compiled))
                   (str/split-lines (str content))))))

(defn- ^{:stratum 0} documents
  "Parse rendered multi-document YAML, or nil when it is not YAML."
  [content]
  (try
    (vec (remove nil? (yaml/parse-string (str content) :load-all true :keywords false)))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} document-findings
  [allow-secret-data? document]
  (let [kind (get document "kind")
        label (str kind "/" (get-in document ["metadata" "name"]))
        secret (when (and (= "Secret" kind) (not allow-secret-data?))
                 (for [field ["data" "stringData"]
                       [k v] (get document field)
                       :when (literal-value? v)]
                   (str label ": " field "." k)))
        config (when (= "ConfigMap" kind)
                 (for [[k v] (get document "data")
                       :when (or (and (credential-name? k) (literal-value? v)) (private-key? v))]
                   (str label ": data." k)))
        env (for [entry (env-entries document)
                  :let [value (get entry "value")]
                  :when (and (not (contains? entry "valueFrom"))
                             (literal-value? value)
                             (or (credential-name? (get entry "name")) (private-key? value)))]
              (str label ": env " (get entry "name")))]
    (concat secret config env)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check
  "Custom detector: (check artifact context). Returns a violation naming where
   plaintext credentials sit in the rendered manifests, or nil."
  [artifact context]
  (let [content (or (:artifact/content artifact) (:content artifact))
        config (get-in context [:policy-pack/rule :rule/detection :detector-config])
        parsed (documents content)
        structural (mapcat (partial document-findings (true? (:allow-secret-data? config)))
                           (filter map? parsed))
        ;; Not YAML, or YAML that is not all manifests: the line patterns apply.
        fallback (when (and (or (nil? parsed) (not-every? map? parsed))
                            (fallback-match? (:fallback-patterns config []) content))
                   [(msg/t :policy/manifest-not-yaml)])
        findings (vec (concat structural fallback))]
    (when (seq findings)
      {:message (msg/t :policy/manifest-credentials {:findings (str/join "; " findings)})
       :findings findings})))

;; Register at load, as the deployment policy detectors do, so the pack's
;; :custom-fn resolves deterministically.
(policy-pack/register-custom-fn! 'ai.miniforge.phase-deployment.manifest-credentials/check check)

(comment
  (check {:artifact/content "kind: ConfigMap\ndata:\n  password: hunter2\n"} {}))
