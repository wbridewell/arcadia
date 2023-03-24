(ns
  ^{:doc "Provides an environment for cued task switching. The tasks are
         parity and magnitude, with two buttons and a rudimentary display. The
         timing for the tasks can be adjusted as needed."}
  arcadia.simulator.environment.cued-task-switching
  (:require [arcadia.simulator.environment.core :refer [Environment]]
            [arcadia.utility [image :as img] [opencv :as cv]]
            [clojure.math.numeric-tower :refer [floor]]
            [clojure.set :refer [difference]]
            [clojure.data.generators :as dgen])
  (:import [javax.swing JFrame]
           [java.awt Color Font RenderingHints]
           [java.awt.image BufferedImage]))

;; This task is set up for consumption by ARCADIA, where we can control
;; the cycle time. If we were going to run humans, we would want to
;; pregenerate all the potential images and display them when called for.

(def jframe (atom nil))

(def height 512)
(def width 512)

(def location
  {:xpos (floor (/ width 2))
   :ypos (floor (/ height 2))
   :xcorner 0
   :ycorner 0})

(def background-color Color/WHITE)
(def foreground-color Color/BLACK)

;; cycle time will be treated as 25 milliseconds.
;; maybe this should be an ARCADIA-wide parameter determined by
;; the particular environment.
(def cycle-time 25)

(def cues ["P" "M"])
(def stimuli ["1" "2" "3" "4" "6" "7" "8" "9"])
(def actions [:b-odd-low :b-even-high])
(def correct-responses {:b-odd-low {"P" #{"1" "3" "7" "9"}
                                    "M" #{"1" "2" "3" "4"}}
                        :b-even-high {"P" #{"2" "4" "6" "8"}
                                      "M" #{"6" "7" "8" "9"}}})


(defn- get-canvas [width height]
  (let [bi (BufferedImage. width height BufferedImage/TYPE_3BYTE_BGR)]
    (doto (.createGraphics bi)
        (.setPaint background-color)
        (.fillRect 0 0 width height)
        .dispose)
    bi))

(def blank-screen (get-canvas width height))

(defn- draw-centered-text [s]
  (let [canvas (get-canvas width height)
        g (.createGraphics canvas)]
    (.setRenderingHints g (RenderingHints. RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON))
    (.setColor g foreground-color)
    (.setFont g (Font. "Arial" Font/PLAIN 80))
    (.drawString g s
                 (- (/ width 2) (int (/ (-> g .getFontMetrics (.stringWidth s)) 2)))
                 (+ (/ height 2) (int (-> g .getFontMetrics .getDescent))))
    (.dispose g)
    canvas))

(defn- state-value [env state-key]
  (-> env :state deref state-key))

(defn- new-state [intertrial-interval n-trials]
  {:display-type :intertrial
   :remaining-time intertrial-interval
   :previous-stimulus nil
   :previous-cue nil
   :current-stimulus nil
   :current-cue nil
   :remaining-trials n-trials})

(defn- next-stimulus [current-stimulus stimuli]
  (dgen/rand-nth (seq (difference (set stimuli) #{current-stimulus}))))

(defn- next-cue [current-cue cues]
  (dgen/rand-nth (seq (difference (set cues) #{current-cue}))))

(defn- draw-screen [env]
  (case (state-value env :display-type)
    :intertrial blank-screen
    :intratrial blank-screen
    :cue (draw-centered-text (state-value env :current-cue))
    :stimulus (draw-centered-text (state-value env :current-stimulus))))

(defn- correct-response? [action cue stimulus]
  (contains? (get (get correct-responses action) cue) stimulus))

;; state
;;   display type: cue, stimulus, intertrial, intratrial
;;   remaining time: number of milliseconds left in the state
;;   previous stimulus: one of the stimuli (to avoid repetition)
;;   previous cue: one of the cues (to control repetition)
;;   current stimulus
;;   current cue
;;   remaining trials: number of trials left

;; may eventually use a cue-type table to ensure an even mix of
;; cue transitions, or we could write code to generate that table
;; in advance.


;; consider adding "render modes" as retrievable properties
;; consider adding "render keywords" as retrievable properties
;; consider a better way to get dimensional parameters

;; uses vars in the namespace: height, width, cues, stimuli, actions.
(defrecord CuedTaskSwitching [n-trials cue-interval stimulus-interval intratrial-interval intertrial-interval state]
  Environment

  (info [env]
        {:dimensions [:height :width]
         :height height
         :width width
         ;; visual degrees isn't important for this task.
         ;; treat the display as 12x12 visual degrees.
         :viewing-width 12
         :increment 0.025 ;; 40 hz
         :render-modes ["human" "buffered-image"]
         :cues cues
         :stimuli stimuli
         :task-parameters {:total-trials n-trials
                           :cue-interval cue-interval
                           :stimulus-interval stimulus-interval
                           :cue-stimulus-interval intratrial-interval
                           :intertrial-interval intertrial-interval}
         ;; button presses
         :actions actions})

  ;; intertrial -> cue -> intratrial -> stimulus -(button press or time out)-> intertrial
  ;; does not display error notification on incorrect response
  ;; returns [observation reward done? diagnostic-info]
  ;;
  ;; as an action, expects either an empty sequence or a sequence
  ;; containing one of the available actions for the environment.
  ;; in this case, "b1" or "b2".
  (step [env action]
        (println "STATE:" (state-value env :remaining-trials)
                 (state-value env :display-type)
                 (state-value env :remaining-time)
                 (state-value env :current-cue)
                 (state-value env :current-stimulus))

        (println "ACTIONS:" action)
        ;(render env "human" nil)
        ;; if trial number is zero, then no change (possibly return a done indicator).
        (cond (zero? (state-value env :remaining-trials))
          {:reward 0 :done? true}

          ;; if change
          ;;   update display type based on state counter, reset state counter if necessary
          (zero? (state-value env :remaining-time))
          (do
            ;; if switching from intertrial to cue, pick a cue uniformly at random
            ;;   (later based on a table determined by n-trials)
            (cond (= (state-value env :display-type) :intertrial)
              (swap! (:state env) assoc
                     :display-type :cue
                     :current-cue (next-cue (state-value env :current-cue) cues)
                     :previous-cue (state-value env :current-cue)
                     :remaining-time (:cue-interval env))

              ;; switching from cue to intratrial
              (= (state-value env :display-type) :cue)
              (swap! (:state env) assoc
                     :display-type :intratrial
                     :remaining-time (:intratrial-interval env))


              ;; if switching from intratrial to stimulus, pick a stimulus uniformly at random from
              ;;   the set minus the previous stimulus
              ;;   (later we'll determine this transition from a precomputed table, too)
              (= (state-value env :display-type) :intratrial)
              (swap! (:state env) assoc
                     :display-type :stimulus
                     :current-stimulus (next-stimulus (state-value env :current-stimulus) stimuli)
                     :previous-stimulus (state-value env :current-stimulus)
                     :remaining-time (:stimulus-interval env))

              ;; if switching from stimulus to intertrial, decrement trial number
              :else
              (swap! (:state env) assoc
                     :display-type :intertrial
                     :remaining-trials (dec (state-value env :remaining-trials))
                     :remaining-time (:intertrial-interval env)))
            {:observation @(:state env) :reward 0 :done? false})

          ;; if button press and in stimulus mode, register response type and move to next trial.
          ;; ignore button presses outside of stimulus mode (or late button presses)
          (and (= (state-value env :display-type) :stimulus)
               (seq action))
          (do
            ;; switch to intertrial mode
            (swap! (:state env) assoc
                   :display-type :intertrial
                   :remaining-trials (dec (state-value env :remaining-trials))
                   :remaining-time (:intertrial-interval env))
            {:observation @(:state env)
             :reward
             (if (correct-response?
                  (first action)
                  (state-value env :current-cue)
                  (state-value env :current-stimulus))
               1
               0)
             :done? false})

          ;; else update state counter if no change
          :else
          (do (swap! (:state env) assoc :remaining-time (- (state-value env :remaining-time) cycle-time))
            {:done? false})))
  (reset [env]
         (reset! state (new-state (:intertrial-interval env) (:n-trials env))))

  ;; Draw for human consumption or get a Java BufferedImage.
  ;; render returns its results in key-value pairs
  (render [env mode close]
          (case mode
            "human"
            (img/display-image! (if @jframe @jframe
                                    (reset! jframe
                                            (doto (JFrame. "Cued Task Switching Environment")
                                              (.setLocation 500 300))))
                                (draw-screen env))

            "buffered-image"
            {:image (draw-screen env)
             :location location}

            "opencv-matrix"
            {:image (img/bufferedimage-to-mat (draw-screen env) cv/CV_8UC3)
             :location location}

            "state-descriptor"
            (let [stage-time   (state-value env (get {:intratrial :intratrial-interval
                                                      :intertrial :intertrial-interval
                                                      :stimulus :stimulus-interval
                                                      :cue :cue-interval} (state-value env :display-type)))]

              {:status (state-value env :display-type)
               :current-cue (state-value env :current-cue)
               :current-stimulus (state-value env :current-stimulus)
               :time (- stage-time (state-value env :remaining-time)) ;; maybe we want this to be the remaining time, but unclear
               :trial (- n-trials (state-value env :remaining-trials))})))

  (close [env] (reset! state {}))

  ;; no randomness
  (seed [env seed-value] nil))

;; the argument must be a map that includes the following keys
;; :n-trials -- the number of trials to run
;; :cues -- a collection of cues to display as strings
;; :stimuli -- a collection of stimuli to display as strings
;; :cue-intervalj -- the length of time to display the cue
;; :stimulus-interval -- the length of time to display the stimulus
;; :intratrial-interval -- the length of time to display a blank screen between the cue and stimulus
;; :intertrial-interval -- the length of time to display a blank screen at the beginning of each trial
(defn configure [env-args]
  (->CuedTaskSwitching (:n-trials env-args)
                      (:cue-interval env-args)
                      (:stimulus-interval env-args)
                      (:intratrial-interval env-args)
                      (:intertrial-interval env-args)
                      (atom (new-state (:intertrial-interval env-args) (:n-trials env-args)))))
