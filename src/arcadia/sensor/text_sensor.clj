(ns arcadia.sensor.text-sensor
  ^{:doc "This sensor reports text-based communication in the enviornment"}
  (:require (arcadia.simulator.environment [core :as env])
            [arcadia.sensor.core :refer [Sensor]]))


;; Every cycle the TextSensor pulls out sequences of text strings from the environment
;;
(defrecord TextSensor [env] Sensor
  (poll [sensor]
        (env/render @(:env sensor) "text" false))
  (swap-environment [sensor new-env]
                    (reset! @(:env sensor) new-env)))

(defn start [environment]
  (->TextSensor (atom environment)))
