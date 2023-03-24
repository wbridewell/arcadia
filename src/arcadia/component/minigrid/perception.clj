(ns arcadia.component.minigrid.perception
  "TODO"
  ;(:import java.awt.Rectangle)
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :as sensor]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.minigrid :as mg]))

(def ^:parameter ^:required sensor "a sensor that provides minigrid input (required)" nil)

(def ^:private ego-grid-size [7 7]) ;; may make this a parameter
(def ^:private ego-column 0)
(def ^:private ego-row (quot (first ego-grid-size) 2))

(defn- get-semantics [mgpoint]
  {:category (mg/category mgpoint)
   :color (mg/color mgpoint)
   :state (mg/door mgpoint)})

;; 7 x 7 observation matrix with the agent at (3,0)
;; get-value expects a (column, row) index
(defn- get-adjacent-semantics [mtx]
  {:left (get-semantics (cv/get-value mtx ego-column (dec ego-row)))
   :front (get-semantics (cv/get-value mtx (inc ego-column) ego-row))
   :right (get-semantics (cv/get-value mtx ego-column (inc ego-row)))
   :on (get-semantics (cv/get-value mtx ego-column ego-row))})

(defn- perceive [obs]
  {:name "minigrid-perception"
   :arguments {:adjacency-info (get-adjacent-semantics (:image obs))
               ;; :mtx (tensor/->jvm (:image obs))
                 ;; :semantic-matrix
               }
   :world nil
   :type "instance"})

(defrecord MinigridPerception [sensor buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (let [data (sensor/poll (:sensor component))]
      (when (:agent-obs data)
        (reset! (:buffer component) (perceive (:agent-obs data))))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridPerception [comp ^java.io.Writer w]
  (.write w (format "MinigridPerception{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->MinigridPerception (:sensor p) (atom nil) p)))
