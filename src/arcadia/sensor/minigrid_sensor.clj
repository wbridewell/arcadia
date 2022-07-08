(ns arcadia.sensor.minigrid-sensor
  ^{:doc "This sensor provides an egocentric view of a minigrid environment."}
  (:require [arcadia.simulator.environment.core :as env]
            [arcadia.utility.opencv :as cv]
            [arcadia.sensor.core :refer [Sensor]]))

;; minigrid places its agent in the (height/2, width-1) cell of the egocentric view. the 
;; left side of the agent is in rows 0->height/2-1 and the right is in rows 
;; height/2+1->height-1. this is a bit odd because when (0,0) is printed as the top left 
;; position (which it is by default), the view looks flipped. further, the full grid 
;; view has (0,0) at the top left, so to ease grid overlay and to avoid a need to translate
;; distances counterintuitively, we will flip the egocentric grid in the sensor.
;;
;; we will also convert the generic numpy tensor representation to an opencv matrix so that
;; we have all the image manipulation routines available for use. 

;; :image is the human readable image of the grid
;; :agent-obs contains the :direction the agent is facing, the :mission string
;; and the :image which is the limited, egocentric view in minigrid format
;; :full-map contains the full view in minigrid format
(defrecord MinigridSensor [env] 
  Sensor
  (poll
   [sensor]
   (env/render @(:env sensor) "egocentric" false))
  ;;  (let [sense-data (env/render @(:env sensor) "egocentric" false)]
  ;;    {:image (cv/->java (:image sense-data))
  ;;     :agent-obs {:direction (-> sense-data :agent-obs :direction)
  ;;                 :mission (-> sense-data :agent-obs :mission)
  ;;                 :image (cv/flip! (cv/->java (-> sense-data :agent-obs :image)) 1)
  ;;                 :location (-> sense-data :agent-obs :location)}
  ;;     :full-map (cv/->java (:full-map sense-data))}))

  (swap-environment [sensor new-env]
                    (reset! (:env sensor) new-env)))

(defn start [environment]
  (->MinigridSensor (atom environment)))
