(ns arcadia.sensor.action-sensor
  ^{:doc "This sensor reports actions that have been sent to the environment"}
  (:require [arcadia.simulator.environment [core :as env]]
            [arcadia.sensor.core :refer [Sensor]]))

;; Every cycle the ActionSensor pulls out sequences of action strings from the environment
;;
(defrecord ActionSensor [env] Sensor
  (poll [sensor]
        (env/render @(:env sensor) "action" false))
  (swap-environment [sensor new-env]
                    (reset! (:env sensor) new-env)))

(defn start [environment]
  (->ActionSensor (atom environment)))
