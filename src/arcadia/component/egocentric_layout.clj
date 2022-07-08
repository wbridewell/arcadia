(ns arcadia.component.egocentric-layout
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.component.core :refer [Component]]))

;; NOTE: I am not sure whether we need to have a separate, egocentric layout
;; if we have an allocentric layout and a position+orientation for the 
;; agent along with the visual input. Presumably we need to generate some 
;; egocentric perspective from allocentric information, but the other way around
;; is probably some sort of SLAM implementation like one of the recent modifications
;; of RatSLAM. As long as we operate in GridWorld environments and map based on 
;; the agent's perspective, we should be able to build a grid map easily enough
;; from egocentric data.

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

(defn- spatial-map [location component]
  {:name "spatial-map"
   :arguments {:perspective "egocentric" :layout location :place "computer"}
   :component component
   :world nil
   :type "instance"})

(defn- egocentrize [segmentation]
  (let [img (-> segmentation :arguments :image)
        regions (map :region (-> segmentation :arguments :segments))]
    (when img
      {:width (cv/width img) :length (cv/height img) :height 0 :units :px
       :objects (mapv #(hash-map :category "object" :location %) regions)})))

(defrecord EgocentricLayout [buffer]
  Component
  (receive-focus
    [component focus content]
    (reset! buffer (spatial-map (egocentrize (d/first-element content :name "image-segmentation")) 
                                component)))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method EgocentricLayout [comp ^java.io.Writer w]
  (.write w (format "EgocentricLayout{}")))

(defn start []
  (->EgocentricLayout (atom nil)))