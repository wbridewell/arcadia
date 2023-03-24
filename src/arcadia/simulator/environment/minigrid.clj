(ns ^{:doc "Defines an interface with the python minigrid RL agent environment"}
 arcadia.simulator.environment.minigrid
  (:require [arcadia.utility.opencv :as cv]
            [arcadia.utility.minigrid :as mg]
            [clojure.data.generators :as dgen]
            [arcadia.simulator.environment.core :refer [Environment]]))

;; minigrid 2.0.0
;; gymnasium 0.26.2

(def ^:private gym? "Has OpenAI gym been loaded for python?" (atom false))
(def ^:private minigrid? "Has the minigrid library been loaded for python?" (atom false))

;;Attempt to load gym and minigrid
(try
  (require '[libpython-clj2.require :refer [require-python]]
           '[libpython-clj2.python :refer [py. py.. py.-] :as py])
  ((resolve 'require-python) '[gymnasium :as gym])
  (reset! gym? true)
  ((resolve 'require-python) '[minigrid :as minigrid])
  (reset! minigrid? true)
  (catch Exception e nil))

(defmacro wmg
  "Code gets runs only if minigrid is available."
  [code]
  (when (and @gym? @minigrid?)
    code))

(defn convert-results [result minigrid-env]
  {:image (cv/->java (cv/cvt-color (wmg (py. minigrid-env "get_frame")) cv/COLOR_RGB2BGR))
   ;; OpenCV represents [heigh width], minigrid encode returns as [width height]
   :full-map (cv/transpose! (cv/->java (wmg #_{:clj-kondo/ignore [:unresolved-symbol]}
                                        (py.. minigrid-env -grid encode))))
   :agent-obs (wmg {:direction (py/->jvm (:direction result))
                    :mission (py/->jvm (:mission result))
                    :image (cv/flip! (cv/->java (:image result)) 1)
                    :location (py/->jvm #_{:clj-kondo/ignore [:unresolved-symbol]}
                               (py.- minigrid-env "agent_pos"))})})

(defrecord Minigrid [minigrid-env observation-buffer]
  Environment
  (info [env]
    {:dimensions [:height :width]
     :height 300
     :width 300
         ;; visual degrees isn't important for this task.
         ;; treat the display as 12x12 visual degrees.
     :viewing-width 12
     :increment 0.025 ;; 40 hz
     :render-modes ["egocentric" "opencv-matrix"]})

  ;; returns [observation reward done? diagnostic-info]
  (step [env actions]
    (when (and actions (seq actions))
      (reset! observation-buffer
              ;; will stop the simulation when the model signals that it has achieved its goal
              (merge {:done? (= "done" (mg/action-name (first actions)))}
                     (convert-results (first (wmg (py. minigrid-env "step" (first actions))))
                                      minigrid-env)))))

  (reset [env]
    (wmg (py. minigrid-env "reset"))
    (reset! observation-buffer nil))

  (render [env mode close]
    (case mode
      "opencv-matrix"
      {:image (cv/->java (cv/cvt-color (wmg (py. minigrid-env "get_frame")) cv/COLOR_RGB2BGR))}

      "egocentric"
      @observation-buffer))

  (close [env] (wmg (py. minigrid-env "close")))
  (seed [env seed-value]
    (wmg (py. minigrid-env "reset" :seed seed-value))
    (reset! observation-buffer nil)))

;;;;;;;;;;;

(defn dummy-action [env]
  (wmg #_{:clj-kondo/ignore [:unresolved-symbol]}
   (py.. env -actions -drop -value)))

(defn configure [env-args]
  (let [env (wmg #_{:clj-kondo/ignore [:unresolved-namespace]}
             (gym/make (:environment env-args)))
        seed  (dgen/int) #_1624845509]
    (println "ENVIRONMENT SEED:" seed)
    (wmg (py. env "reset" :seed seed))
    ;; use a drop action to fill the results buffer initially.
    (->Minigrid env (atom
                     (convert-results (first (wmg (py. env "step" (dummy-action env)))) env)))))
