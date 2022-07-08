(ns
  ^{:doc "This sensor implements an unmoving camera with fixed width and height
         that looks out on a simulation environment."}
  arcadia.sensor.stable-viewpoint
  (:require (arcadia.simulator.environment [core :as env])
            [arcadia.sensor.core :refer [Sensor]]))

(defn camera-width
  "Pixel width of the camera."
  [camera-sensor]
  (-> camera-sensor :env deref env/info :width))

(defn camera-height
  "Pixel height of the camera."
  [camera-sensor]
  (-> camera-sensor :env deref env/info :height))

;; The following functions are used for working with psychological stimuli.
(defn camera-viewing-width
  "Degrees of visual angle for width of the camera."
  [camera-sensor]
  (-> camera-sensor :env deref env/info :viewing-width))

(defn camera-viewing-height
  "Degrees of visual angle for height of the camera."
  [camera-sensor]
  (* (camera-height camera-sensor)
     (/ (camera-viewing-width camera-sensor) (camera-width camera-sensor))))

(defn camera-increment
  "Amount of time that passes per cycle."
  [camera-sensor]
  (-> camera-sensor :env deref env/info :increment))


(defrecord StableViewpoint [env]
  Sensor
  (poll [sensor] (env/render @env "opencv-matrix" false))
  (swap-environment [sensor new-env]
                    (reset! (:env sensor) new-env)))

(defn start
  "Attach an instance of the sensor to a specific environment."
  [environment]
  (->StableViewpoint (atom environment)))
