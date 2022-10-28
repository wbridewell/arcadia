(ns ^{:doc "Defines an interface with the python minigrid RL agent environment"}
 arcadia.simulator.environment.minigrid
  (:require [arcadia.utility.opencv :as cv]
            [arcadia.simulator.environment.core :refer [Environment]]))

(def ^:private gym? "Has OpenAI gym been loaded for python?" (atom false))
(def ^:private minigrid? "Has the minigrid library been loaded for python?" (atom false))

;;Attempt to load gym and minigrid
(try
  (require '[libpython-clj2.require :refer [require-python]]
           '[libpython-clj2.python :refer [py. py.. py.-] :as py])
  ((resolve 'require-python) '[gym :as gym])
  (reset! gym? true)
  ((resolve 'require-python) '[gym_minigrid :as minigrid])
  (reset! minigrid? true)
  (catch Exception e nil))

(defmacro wmg
  "Code gets runs only if minigrid is available."
  [code]
  (when (and @gym? @minigrid?)
    code))

(defrecord Minigrid [minigrid-env render-buffer]
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
      (wmg (py. minigrid-env "step" (first actions)))
      (reset! (:render-buffer env) nil)))

  (reset [env]
    (wmg (py. minigrid-env "reset"))
    (reset! (:render-buffer env) nil))
  
  (render [env mode close]
    (case mode
      "opencv-matrix"
      {:image (cv/->java (cv/cvt-color (wmg (py. minigrid-env "render" "nothuman")) cv/COLOR_RGB2BGR))}

      "egocentric"
      (or @(:render-buffer env)
          (reset! (:render-buffer env) 
                  {:image (cv/->java (cv/cvt-color (wmg (py. minigrid-env "render" "nothuman")) cv/COLOR_RGB2BGR))
                   :full-map (cv/transpose! (cv/->java (wmg #_{:clj-kondo/ignore [:unresolved-symbol]}
                                                        (py. (py.- minigrid-env "grid") "encode"))))
                   :agent-obs (wmg (let [x (py. minigrid-env "gen_obs")]
                                     {:direction (py/->jvm (:direction x))
                                      :mission (py/->jvm (:mission x))
                                      :image (cv/flip! (cv/->java (:image x)) 1)
                                      :location (py/->jvm (py.- minigrid-env "agent_pos"))}))
                   }))))

  (close [env] (wmg (py. minigrid-env "close")))

  ;; no randomness
  (seed [env seed-value] nil))

;;;;;;;;;;;

(defn configure [env-args]
  (let [env (wmg #_{:clj-kondo/ignore [:unresolved-namespace]}
             (gym/make (:environment env-args)))
        seed  #_(rand-int Integer/MAX_VALUE) 1008428187]
    (println "ENVIRONMENT SEED:" seed)
    (wmg (py. env "seed" seed))
    (wmg (py. env "reset"))
    (->Minigrid env (atom nil))))
