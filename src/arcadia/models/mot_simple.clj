(ns arcadia.models.mot-simple
  "Simple multiple object tracking model for instructional purposes."

  (:require [arcadia.utility.model :as model]
            [arcadia.architecture.registry :refer [get-sensor]]
            [arcadia.models.core]
            [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.general :as g]
            [arcadia.utility.descriptors :as d]
            [clojure.java.io]))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))

(defn select-focus [content]
  (let [fixations (d/filter-elements content :name "fixation")]
    (or (d/first-element content :name "object" :world nil)
        (d/rand-element (att/remove-object-fixations fixations content))
        (d/rand-element fixations)
        (g/rand-if-any (seq content)))))

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
   (model/add display.fixations {:sensor (get-sensor :stable-viewpoint)
                                 :x 40 :y 0}) 
   (model/add display.objects {:sensor (get-sensor :stable-viewpoint)
                               :x 40 :y 600})
   (model/add display.status {:x 590 :y 0})))

(defn example-run
  ([]
   (example-run 250 false false))
  ([cycles]
   (example-run cycles false false))
  ([cycles debug?]
   (example-run cycles debug? false))
  ([cycles debug? record?]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.mot-simple
    'arcadia.simulator.environment.video-player
    {:video-path (.getPath (clojure.java.io/resource "test-files/MOT_Extrap_2Targets.mp4"))} 
    cycles
    :cycles-before-pause (when debug? nil) ;;change nil to a number to pause after that many cycles
    :record? record?
    ;:random-generator (java.util.Random. 3)
    :component-setup 
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (model/setup
     (when debug? (model/add display.controls {:x 590 :y 200}))
     (when debug? (model/add display.scratchpad
                             {:sensor (get-sensor :stable-viewpoint) :x 930 :y 0}))))))