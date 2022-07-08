(ns arcadia.component.minigrid.egocentric-map
  "Produces
   * spatial-map
       :image - the minigrid representation as an opencv matrix
       :location - the location of the agent in the total space
       :direction - the direction the agent is facing
       :sensor - the sensor that produced the map
       :perspective - \"egocentric\""
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :as sensor]))


(def ^:parameter ^:required sensor "a sensor that provides minigrid input (required)" nil)

(defn- interlingua [obs component]
  {:name "spatial-map"
   :arguments {:layout (:image obs)
               :location (:location obs)
               :direction (:direction obs)
               :place "minigrid"
               :perspective "egocentric"}
   :world nil
   :source component
   :type "instance"})

(defrecord MinigridEgocentricMap [sensor buffer]
  Component
  (receive-focus
    [component focus content]
    (when-let [data (sensor/poll (:sensor component))]
      (reset! (:buffer component) (interlingua (:agent-obs data) component))))
  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method MinigridEgocentricMap [comp ^java.io.Writer w]
  (.write w (format "MinigridEgocentricMap{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->MinigridEgocentricMap (:sensor p) (atom nil))))
