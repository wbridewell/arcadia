(ns ^{:doc "Defines a simulation environment for a billiard ball table"}
  arcadia.simulator.environment.billiards
  (:require [arcadia.simulator.environment [jbox2d :as jbox2d]]
            [arcadia.simulator.environment.core :refer [Environment]])
  (:import javax.swing.JFrame
           [java.awt Color]))

(declare info step)

(def default-parameters
  {:goal-color (Color. 255 153 153)
   :wall-outline-weight 1.5
   :text-box? false
   :text-box-x 0
   :text-box-y 0
   :text-box-w 400
   :text-box-h 150})

;;-------------------------------Rendering-----------------------------------

(defrecord Billiards [state jframe parameters] Environment
  (info [env]
        (assoc parameters
               :dimensions [:height :width]
               :viewing-height 13.5
               :viewing-width 18
               :render-modes ["human" "buffered-image" "opencv-matrix" "text"]))

  ;; returns [observation reward done? diagnostic-info]
  (step [env actions] (jbox2d/step-default env actions))
  (reset [env] (jbox2d/reset-default env))
  (render [env mode close] (jbox2d/render-default 'arcadia.simulator.environment.billiards
                                                  env mode close))
  (close [env] (reset! state {}))
  ;; no randomness
  (seed [env seed-value] nil))

;;-----------------------------------Border Walls-------------------------------
(defn border-walls
  "Get a sequence of wall-specs for the border walls in the Billiards environment"
  [{:keys [width height wall-thickness background-color wall-outline-weight]}]
  [(jbox2d/wall-spec "top-wall" (/ width 2.0) (/ wall-thickness 2.0)
                     width wall-thickness :outline background-color
                     :outline-weight wall-outline-weight)
   (jbox2d/wall-spec "bottom-wall" (/ width 2.0) (- height (/ wall-thickness 2.0))
                     width wall-thickness :outline background-color
                     :outline-weight wall-outline-weight)
   (jbox2d/wall-spec "upper-left-wall" (/ wall-thickness 2.0) (/ height 6.0)
                     wall-thickness (/ height 3.0) :outline background-color
                     :outline-weight wall-outline-weight)
   (jbox2d/wall-spec "lower-left-wall" (/ wall-thickness 2.0) (* height (/ 5.0 6.0))
                     wall-thickness (/ height 3.0) :outline background-color
                     :outline-weight wall-outline-weight)])


;;------------------------------------------------------------------------------
;; wall-specs is a seq of hashmaps created by the function jbox2d/wall-spec
;; ball-specs is a seq of hashmaps created by the function jbox2d/ball-spec
;; advance is the # of seconds to pre-run the environment
;; text-box? optionally adds a JTextArea at the defined location for object "speech"
(defn configure
  "Configure the billiards environment taking wall-specs, ball-specs,
  and advance as keyword arguments. the :advance argument instructs the
  environment to run for :advance seconds before starting the model."
  ([{:keys [specs advance x y]
     :or {specs [] advance 0.0 x 0.0 y 0.0}
     :as env-args}]
   (let [parameters (merge jbox2d/default-parameters default-parameters env-args)
         scaled-parameters (jbox2d/scale-parameters parameters)
         scaled-specs (map #(jbox2d/scale-spec % (:scale parameters))
                           (into (border-walls parameters) specs))
         env (->Billiards (atom (jbox2d/new-state scaled-specs (:contact-listeners parameters)))
                          (doto (JFrame. "Billiard Ball Environment")
                            (.setSize (:width scaled-parameters) (:height scaled-parameters))
                            (.setLocation x y))
                          (assoc scaled-parameters :x x :y y
                                 :specs scaled-specs
                                 :text-box (jbox2d/text-box parameters)))]
     ;; make the environment accessible to any contact listeners
     (doseq [l (:contact-listeners parameters)]
       (when (instance? clojure.lang.IAtom (:env l))
         (reset! (:env l) env)))
     ;; advance the environment
     (dotimes [i (/ advance (-> env info :increment))] (step env nil))
     env)))
