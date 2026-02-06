(ns ai.miniforge.cli.web.sse
  "Server-Sent Events for workflow streaming."
  (:require
   [cheshire.core :as json]
   [org.httpkit.server :as http]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.web.response :as response]))

(def ^:private streams (atom {}))

(defn- get-or-create-stream [workflow-id]
  (or (get @streams workflow-id)
      (let [stream (es/create-event-stream)]
        (swap! streams assoc workflow-id stream)
        stream)))

(defn- on-open [workflow-id channel]
  (let [event-stream (get-or-create-stream workflow-id)
        sub-id (random-uuid)]
    (swap! streams assoc-in [workflow-id :subscribers channel] sub-id)
    (http/send! channel (response/sse-headers) false)
    (es/subscribe! event-stream sub-id
                   (fn [event]
                     (http/send! channel
                                 (str "event: " (name (:event/type event)) "\n"
                                      "data: " (json/generate-string event) "\n\n")
                                 false)))))

(defn- on-close [workflow-id channel]
  (when-let [sub-id (get-in @streams [workflow-id :subscribers channel])]
    (when-let [event-stream (get @streams workflow-id)]
      (es/unsubscribe! event-stream sub-id))
    (swap! streams update-in [workflow-id :subscribers] dissoc channel)))

(defn handle-stream [workflow-id req]
  (http/as-channel req
    {:on-open (partial on-open workflow-id)
     :on-close (partial on-close workflow-id)}))

(defn register! [workflow-id event-stream]
  (swap! streams assoc workflow-id event-stream)
  workflow-id)

(defn unregister! [workflow-id]
  (swap! streams dissoc workflow-id)
  nil)

(defn get-stream [workflow-id]
  (get @streams workflow-id))
