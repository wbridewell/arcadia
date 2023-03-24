(ns arcadia.component.allocentric-layout
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.geometry :as geo]
            [clojure.math.numeric-tower :as math]
            [arcadia.component.core :refer [Component]]))

;;
;;
;; Focus Responsive
;;
;;
;; Default Behavior
;;
;;
;; Produces
;;
;;

;; produce a static representation
;; 17x18 ft lab space represented as a grid w/ 1 sqft cells
;; computer monitor at (3,6)
;; agent at (4,6)
;; should probably also include height and orientation
;;
;; map incoming visual information to the computer monitor location
;; in this case, we only need a room that can be the top-level node
;; that items on the computer screen can be associated with. 
;; the interior data structure for the room is not particularly important,
;; and will undoubtedly change when we move to a gridworld setting.
;;
;; i'm guessing that attending to the room would lead to an attempt to 
;; resolve its container node and the room's location within it. 
(def room
  {:layout [0 0 0 0 1 0 0 0 0] ;; 3x3 grid with computer in the center of the room
   :place "laboratory"
   :container nil})

(defn- spatial-map [location location-name component]
  {:name "spatial-map"
   :arguments {:perspective "allocentric" 
               :layout location 
               :place location-name
               :container room}
   :component component
   :world nil
   :type "instance"})

(defn- assign-to-cell [width length objects]
  (group-by #(+ (first %) (* 3 (second %)))
            (map #(vector (math/floor (* (/ (geo/center-x %) width) 3))
                          (math/floor (* (/ (geo/center-y %) length) 3)))
                 objects)))

;; only a 2D grid at the moment, no height aspect
;; for displays, the x axis will map to width and y axis to length
(defn- grid-array [cell-map]
  (mapv #(count (get cell-map % [])) (range 9)))

;; NOTE: this function assumes that the system is looking at a 2D layout on a computer display
(defn- allocentrize-display [segmentation]
  (let [img (-> segmentation :arguments :image)
        regions (map :region (-> segmentation :arguments :segments))]
    (when img 
      (grid-array (assign-to-cell (cv/width img) (cv/height img) regions)))))

;; NOTE: This implementation is for simulations of experiments where the 
;; visual information coming in is the contents of a computer screen in 
;; 2D and we are generating an allocentric representation of the layout 
;; of objects on that screen. The perspective and the boundaries are 
;; assumed to be fixed.

(defrecord AllocentricLayout [buffer]
  Component
  (receive-focus
    [component focus content]
    (let [segmentation (d/first-element content :name "image-segmentation")]
      (reset! buffer (spatial-map (allocentrize-display segmentation) "computer" component))))
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method AllocentricLayout [comp ^java.io.Writer w]
  (.write w (format "AllocentricLayout{}")))

(defn start []
  (->AllocentricLayout (atom nil)))