(ns ^{:doc "Defines a simulation environment for a 2D road"}
  arcadia.simulator.environment.road
  (:require [arcadia.simulator.environment.jbox2d :as jbox2d]
            [arcadia.simulator.environment.core :refer [Environment info step]])
  (:import javax.swing.JFrame
           java.awt.Color))

(def default-parameters
  {:road-color (.darker Color/GRAY)
   :stripe-color (:background-color jbox2d/default-parameters)
   :road-wall-color (:background-color jbox2d/default-parameters)
   :text-box? false
   :text-box-x 0
   :text-box-y 0
   :text-box-w 400
   :text-box-h 150})

;;-------------------------------Rendering-----------------------------------


(defrecord Road [state jframe parameters] Environment
  (info [env]
        (assoc parameters
               :dimensions [:height :width]
               :viewing-height 13.5
               :viewing-width 18
               :render-modes ["human" "buffered-image" "opencv-matrix" "text"]))

  ;; returns [observation reward done? diagnostic-info]
  (step [env actions] (jbox2d/step-default env actions))
  (reset [env] (jbox2d/reset-default env))
  (render [env mode close] (jbox2d/render-default 'arcadia.simulator.environment.road env mode close))
  (close [env] (reset! state {}))
  ;; no randomness
  (seed [env seed-value] nil))


;;------------------------------------------------------------------------------
;; wall-specs is a seq of hashmaps created by the function wall-spec
;; ball-specs is a seq of hashmaps created by the function ball-spec
;; advance is the # of seconds to pre-run the environment
;; text-box? optionally adds a JTextArea at the defined location for object "speech"
(defn configure
  "Configure the road environment taking wall-specs, ball-specs,
  and advance as keyword arguments. the :advance argument instructs the
  environment to run for :advance seconds before starting the model."
  ([{:keys [specs advance x y]
     :or {specs [] advance 0.0 x 0.0 y 0.0}
     :as env-args}]
   (let [parameters (merge jbox2d/default-parameters default-parameters env-args)
         scaled-parameters (jbox2d/scale-parameters parameters)
         scaled-specs (map #(jbox2d/scale-spec % (:scale parameters)) specs)
         env (->Road (atom (jbox2d/new-state scaled-specs))
                     (doto (JFrame. "Road Environment")
                       (.setSize (:width scaled-parameters) (:height scaled-parameters))
                       (.setLocation x y))
                     (assoc scaled-parameters :x x :y y
                            :specs scaled-specs
                            :text-box (jbox2d/text-box parameters)))]
     ;; advance the environment
     (dotimes [i (/ advance (-> env info :increment))] (step env nil))
     env)))
