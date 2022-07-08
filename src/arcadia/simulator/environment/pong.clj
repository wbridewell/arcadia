(ns ^{:doc "Defines a simulation environment for a pong table"}
  arcadia.simulator.environment.pong
  (:require [arcadia.simulator.environment.jbox2d :as jbox2d]
            [arcadia.simulator.environment.core :refer [Environment info step]])
  (:import [java.awt Color BasicStroke]
           javax.swing.JFrame))

(def ^:private goal-color (Color. 255 153 153))

;;-------------------------------Rendering-----------------------------------
(defn get-canvas
  "Create a blank image to be drawn over and displayed on the JFrame"
  [env]
  (let [bi (jbox2d/get-canvas env)]
    (doto (.createGraphics bi)
      ;; draw the goal
      (.setPaint goal-color)
      (.fillRect 0 0 (-> env info :wall-thickness) (-> env info :height))

      ;; outline the goal in white
      (.setPaint (-> env info :background-color))
      (.setStroke (-> env info :wall-outline-weight BasicStroke.))
      (.drawRect 0 0 (-> env info :wall-thickness) (-> env info :height))
      .dispose)
    bi))

;;--------------------------Simulation Environment------------------------------
(defrecord Pong [state jframe parameters] Environment
  (info [env]
        (assoc parameters
               :dimensions [:height :width]
               :viewing-height 13.5
               :viewing-width 18
               :render-modes ["human" "buffered-image" "opencv-matrix" "action" "text"]))

  ;; returns [observation reward done? diagnostic-info]
  (step [env actions] (jbox2d/step-default env actions))
  (reset [env] (jbox2d/reset-default env))
  (render [env mode close] (jbox2d/render-default 'arcadia.simulator.environment.pong env mode close))
  (close [env] (reset! state {}))
  ;; no randomness
  (seed [env seed-value] nil))

;;-----------------------------------Border Walls-------------------------------
(defn border-walls
  "Get a sequence of wall-specs for the border walls in the Pong environment"
  [{:keys [width height wall-thickness background-color]}]
  [(jbox2d/wall-spec "top-wall"
                     (/ (+ width wall-thickness) 2.0)
                     (/ wall-thickness 2.0)
                     (- width (* 3 wall-thickness))
                     wall-thickness :outline background-color)
   (jbox2d/wall-spec "bottom-wall"
                     (/ (+ width wall-thickness) 2.0)
                     (- height (/ wall-thickness 2.0))
                     (- width (* 3 wall-thickness))
                     wall-thickness :outline background-color)
   (jbox2d/wall-spec "right-wall"
                     (- width (/ wall-thickness 2.0))
                     (/ height 2.0)
                     wall-thickness
                     height :outline background-color)])

;;------------------------------------------------------------------------------
;; wall-specs is a seq of hashmaps created by the function wall-spec
;; ball-specs is a seq of hashmaps created by the function ball-spec
;; advance is the # of seconds to pre-run the environment
(defn configure
  "Configure the billiards environment taking wall-specs, ball-specs,
  and advance as keyword arguments. the :advance argument instructs the
  environment to run for :advance seconds before starting the model."
  ([{:keys [specs advance x y]
     :or {specs [] advance 0.0 x 0.0 y 0.0}
     :as env-args}]
   (let [parameters (merge jbox2d/default-parameters env-args)
         scaled-parameters (jbox2d/scale-parameters parameters)
         scaled-specs (map #(jbox2d/scale-spec % (:scale parameters))
                            (into (border-walls parameters) specs))
         env (->Pong (atom (jbox2d/new-state scaled-specs))
                     (doto (JFrame. "Billiard Ball Environment")
                       (.setSize (:width scaled-parameters) (:height scaled-parameters))
                       (.setLocation x y))
                     (assoc scaled-parameters :x x :y y
                            :specs scaled-specs))]

     ;; advance the environment
     (dotimes [i (/ advance (-> env info :increment))] (step env nil))
     env)))
