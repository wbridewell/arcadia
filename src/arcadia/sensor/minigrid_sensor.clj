(ns arcadia.sensor.minigrid-sensor
  "This sensor provides an egocentric view of a minigrid environment.
   
   Produces a map containing
   :image - the human viewable image of the minigrid environment
   :full-map - the minigrid representation of the full map, which includes object
               categories, color, and state information
   :agent-obs - a map that includes the :direction the agent is facing, the :location
                of the agent in the full map, the :mission string, and an :image that
                includes the minigrid representations as in :full-map but is limited 
                to the agent's view.
   The :image matrix in :agent-obs is by default a 7x7 grid with the agent in cell 
   [0,3] facing to the right. This positioning differs from the default minigrid 
   data, which places the agent at (height/2, width-1) of this egocentric view. This 
   adjustment is made in the sensor so that overlaying on the full-map view is more 
   intuitive.
   
   :image, :full-map, and the :image in :agent-obs are Java style OpenCV matrices.
   "
  (:require [arcadia.simulator.environment.core :as env]
            [arcadia.sensor.core :refer [Sensor]]))

(defrecord MinigridSensor [env] 
  Sensor
  (poll [sensor] (env/render @(:env sensor) "egocentric" false))
  (swap-environment [sensor new-env] (reset! (:env sensor) new-env)))

(defn start [environment]
  (->MinigridSensor (atom environment)))
