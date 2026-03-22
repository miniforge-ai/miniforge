(ns ai.miniforge.cli.messages
  "Resource-backed message catalog for shared CLI user-facing copy."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.resource-config :as resource-config]))

;------------------------------------------------------------------------------ Layer 0
;; Catalog loading

(def default-locale "en-US")

(defn- locale-resource
  [locale]
  (str "config/cli/messages/" locale ".edn"))

(defn- lang->locale
  "Convert POSIX LANG (e.g. 'en_US.UTF-8') to BCP 47 tag (e.g. 'en-US')."
  [lang]
  (when-let [base (some-> lang (str/split #"\.") first not-empty)]
    (str/replace base "_" "-")))

(defn active-locale
  []
  (or (some-> (System/getenv "MINIFORGE_LOCALE") str/trim not-empty)
      (lang->locale (System/getenv "LANG"))
      default-locale))

(defn catalog
  "Load the active message catalog, falling back to English."
  ([] (catalog (active-locale)))
  ([locale]
   (let [catalog-data (resource-config/merged-resource-config (locale-resource locale)
                                                              :cli/messages
                                                              {})]
     (if (or (= locale default-locale) (seq catalog-data))
       catalog-data
       (catalog default-locale)))))

;------------------------------------------------------------------------------ Layer 1
;; Template rendering

(defn- render-string
  [template params]
  (reduce-kv (fn [rendered key value]
               (str/replace rendered
                            (str "{" (name key) "}")
                            (str value)))
             template
             params))

(defn- render-value
  [value params]
  (cond
    (string? value) (render-string value params)
    (vector? value) (mapv #(render-value % params) value)
    (map? value) (into {} (map (fn [[key entry]]
                                 [key (render-value entry params)]))
                     value)
    :else value))

(defn t
  "Return a rendered message value for `message-key`.

   Supports strings, vectors, and maps loaded from EDN resources."
  ([message-key]
   (t message-key {}))
  ([message-key params]
   (if-let [value (get (catalog) message-key)]
     (render-value value params)
     (throw (ex-info "Missing CLI message key"
                     {:message-key message-key
                      :locale (active-locale)})))))
