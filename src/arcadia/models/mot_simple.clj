(ns arcadia.models.mot-simple
  "Simple multiple object tracking model for instructional purposes."

  (:require [arcadia.utility.model :as model]
            [arcadia.architecture.registry :refer [get-sensor]]
            [arcadia.models.core]
            [clojure.java.io]))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)})
   (model/add highlighter.vstm)
   (model/add highlighter.color
              {:sensor (get-sensor :stable-viewpoint)})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)})
   (model/add object-file-binder)
   (model/add vstm)
   (model/add display.objects {:x 0 :y 0})
   (model/add display.fixations {:sensor (get-sensor :stable-viewpoint)
                                 :x 420 :y 0})))

(defn example-run
  ([]
   (example-run 250))
  ([cycles]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.mot-simple
    'arcadia.simulator.environment.video-player
    {:video-path (.getPath (clojure.java.io/resource "test-files/MOT_Extrap_2Targets.mp4"))}
    cycles)))
