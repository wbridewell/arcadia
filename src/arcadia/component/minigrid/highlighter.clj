(ns arcadia.component.minigrid.highlighter
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

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

(defn- make-fixation [segment]
  {:name "fixation"
   :arguments {:segment segment :reason "minigrid"}
   :world nil
   :type "instance"})

(defrecord MinigridHighlighter [buffer]
  Component
  (receive-focus
    [component focus content]
    (->> (d/first-element content :name "image-segmentation")
         :arguments :segments
         (map #(make-fixation %))
         (filter some?)
         (reset! (:buffer component))))
  
  (deliver-result
    [component]
    @buffer))

(defmethod print-method MinigridHighlighter [comp ^java.io.Writer w]
  (.write w (format "MinigridHighlighter{}")))

(defn start []
  (->MinigridHighlighter (atom nil)))