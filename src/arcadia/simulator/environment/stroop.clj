(ns arcadia.simulator.environment.stroop)

(ns
 ^{:doc "Provides an environment for building models that exhibit the stroop effect."}
 arcadia.simulator.environment.stroop
  (:require [arcadia.simulator.environment.core :refer [Environment]]
            [arcadia.utility [image :as img] [opencv :as cv]]
            [clojure.math.numeric-tower :refer [floor]]
            [clojure.set :refer [difference]]
            [clojure.data.generators :as dgen])
  (:import [javax.swing JFrame]
           [java.awt Color Font RenderingHints]
           [java.awt.image BufferedImage]))


(def ^:parameter color-onset 0)
(def ^:parameter trial-type {:congruent 1 :incongruent 1 :neutral 1})

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

(def display-colors "map of color name to color value" {:red Color/RED :blue Color/BLUE :green Color/GREEN
                                                        ;; for SOA trials
                                                        :black Color/ORANGE})

(def cycle-time "ARCADIA cycle time in milliseconds" 25)

(def colors "colors in stroop" [:red :blue :green])
(def words "words in stroop" (conj (into [] (map name colors))))
(def neutral-word "word to use in neutral trials" "NEUTRAL")
(def default-color-onset "ms between word and color presentation, can be negative" 0)
(def tasks "different tasks available" [:word :color])
(def actions "stroop environment action commands" [:say-red :say-blue :say-green :go-button])
;; NOTE: replace with a generic function once everything is working
(def correct-responses {:say-red {:word "red"
                                  :color :red}
                        :say-blue {:word "blue"
                                   :color :blue}
                        :say-green {:word "green"
                                    :color :green}})
(def default-trial-weights "weights on trial types"
  {:congruent 5
   :incongruent 5})

(defn- get-canvas [width height]
  (let [bi (BufferedImage. width height BufferedImage/TYPE_3BYTE_BGR)]
    (doto (.createGraphics bi)
      (.setPaint background-color)
      (.fillRect 0 0 width height)
      .dispose)
    bi))

(def blank-screen (get-canvas width height))

(defn- draw-centered-text [s & {:keys [color] :or {color foreground-color}}]
  (let [canvas (get-canvas width height)
        g (.createGraphics canvas)]
    (.setRenderingHints g (RenderingHints. RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON))
    (.setColor g (get display-colors color))
    (.setFont g (Font. "Arial" Font/PLAIN 80))
    (.drawString g s
                 (- (/ width 2) (int (/ (-> g .getFontMetrics (.stringWidth s)) 2)))
                 (+ (/ height 2) (int (-> g .getFontMetrics .getDescent))))
    (.dispose g)
    canvas))

(defn- state-value [env state-key]
  (-> env :state deref state-key))

(defn- new-state [n-trials task]
  {:display-type :intertrial
   :current-task task
   :current-time 0
   :current-stimulus nil
   :current-trial-type nil
   :previous-stimulus nil
   :previous-trial-type nil
   :sum-rt {:congruent [0 0] :incongruent [0 0] :neutral [0 0]}
   :remaining-trials n-trials})

(defn- congruent-stimulus []
  (let [idx (dgen/uniform 0 (count colors))]
    {:color (get colors idx) :word (get words idx)}))

(defn- incongruent-stimulus []
  (let [idx (dgen/uniform 0 (count colors))]
    {:color (get colors idx)
     :word (dgen/rand-nth (seq (difference (into #{} words) #{(get words idx)})))}))

(defn- neutral-stimulus []
  (let [idx (dgen/uniform 0 (count colors))]
    {:color (get colors idx) :word neutral-word}))

;; don't worry about proportionality concerns for now
(defn- next-stimulus [condition]
  (case condition
    :congruent (congruent-stimulus)
    :incongruent (incongruent-stimulus)
    :neutral (neutral-stimulus)))

(defn- draw-screen [env]
  (println (state-value env :current-time) (:color-onset env))

  (case (state-value env :display-type)
    :intertrial blank-screen
    :stimulus (draw-centered-text (if (< (state-value env :current-time) (- (:color-onset env)))
                                    "iiiii"                                   
                                    (:word (state-value env :current-stimulus)))
                                  :color (if (< (state-value env :current-time) (:color-onset env))
                                           :black
                                           (:color (state-value env :current-stimulus))))))

;; for Stroop, the instruction will usually be :color
;; we can use :word to get the reading time for calculating the stroop effect
(defn- correct-response? [action instruction stimulus]
  (= (-> correct-responses action instruction)
     (instruction stimulus)))

;; state
;;   display type: stimulus, intertrial
;;   current time: number of milliseconds in the state
;;   previous stimulus: one of the stimuli (to avoid repetition)
;;   current stimulus
;;   remaining trials: number of trials left

;; uses vars in the namespace: height, width, colors, words, actions.
(defrecord Stroop [n-trials weights color-onset state]
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
     :stimuli {:words words :colors colors}
     :task-parameters {:total-trials n-trials :weights weights :color-onset color-onset}
     :actions actions})

  ;; intertrial -(button press)-> cue -(vocalization)-> intratrial
  ;; does not display error notification on incorrect response
  ;; returns [observation reward done? diagnostic-info]
  ;;
  ;; as an action, expects either an empty sequence or a sequence
  ;; containing one of the available actions for the environment.
  (step [env action]
        ;; (println "STATE:" (state-value env :remaining-trials)
        ;;          (state-value env :display-type)
        ;;          (state-value env :current-time)
        ;;          (state-value env :current-stimulus))

        ;; (println "ACTIONS:" action)
        ;; if trial number is zero, then no change (possibly return a done indicator).
    (cond (zero? (state-value env :remaining-trials))
          {:observation nil
           :reward 0
           :done? true}

          ;; intertrial and we get a button press, switch to the stimulus
          ;; restart the counter
          (and (= (state-value env :display-type) :intertrial)
               (seq action)) ;; can only be the go-button action

          (do (let [trial-type (dgen/weighted (:weights env))]
                (swap! (:state env) assoc
                       :display-type :stimulus
                       :current-trial-type trial-type
                       :current-stimulus (next-stimulus trial-type)
                       :previous-trial-type (state-value env :current-trial-type)
                       :previous-stimulus (state-value env :current-stimulus)
                       :current-time 0))
              {:observation @(:state env)
               :reward 0
               :done? false})

          ;; if vocalization occurs in stimulus mode, register response type and move to next trial.
          (and (= (state-value env :display-type) :stimulus)
               (seq action)) ;; can only be one of the vocalize actions :say-blue etc.
          (do
            (println "Trial #:" (- (:n-trials env) (state-value env :remaining-trials)))
            (println "Trial RT:" (state-value env :current-time))
            (println "Trial Type:" (state-value env :current-trial-type))
            (println "RT Sums:" (state-value env :sum-rt))
            ;; switch to intertrial mode

            (let [cumulative-results ((state-value env :current-trial-type) (state-value env :sum-rt))
                  trial (- (:n-trials env) (state-value env :remaining-trials))
                  ;; since we're modifying color-onset for SOA and not a more general 
                  ;; "onset of relevant feature," we need to change how we calculate
                  ;; response time based on the task we are doing on this trial.
                  response-time (if (= :word-reading (state-value env :current-task))
                                  ;; word-reading
                                  (+ (state-value env :current-time)
                                     (min 0 (+ (:color-onset env))))
                                  ;; color-naming
                                  (- (state-value env :current-time)
                                     (max 0 (+ (:color-onset env)))))
                  trial-type (state-value env :current-trial-type)
                  correct (correct-response?
                           (first action)
                           :color ;; for stroop test, we're just going to do color-naming cases
                           (state-value env :current-stimulus))]
              (swap! (:state env) assoc
                     :display-type :intertrial
                     :sum-rt (merge (state-value env :sum-rt)
                                    {(state-value env :current-trial-type)
                                     [(+ (first cumulative-results) (state-value env :current-time))
                                      (inc (second cumulative-results))]})
                     :remaining-trials (dec (state-value env :remaining-trials))
                     :current-time 0)
              {:observation @(:state env)
               :reward (if correct 1 -1)
               :done? false
               :data {:trial trial
                      :trial-type trial-type
                      :previous-trial-type (state-value env :previous-trial-type)
                      :response-time response-time
                      :correct correct}
               :message (-> action first name)
               :increment-message-count? true}))

      ;; else update state counter if no change
          :else
          (do
            (swap! (:state env) assoc :current-time (+ (state-value env :current-time) cycle-time))
            {:observation @(:state env)
             :reward 0
             :done? false})))

  (reset [env]
    (reset! state (new-state (:n-trials env) (state-value env :current-task))))

  ;; Draw for human consumption or get a Java BufferedImage.
  ;; render returns its results in key-value pairs
  (render [env mode close]
    (case mode
      "human"
      (img/display-image! (if @jframe @jframe
                              (reset! jframe
                                      (doto (JFrame. "Stroop Effect Environment")
                                        (.setLocation 500 300))))
                          (draw-screen env))

      "buffered-image"
      {:image (draw-screen env)
       :location location}

      "opencv-matrix"
      {:image (img/bufferedimage-to-mat (draw-screen env) cv/CV_8UC3)
       :location location}

      "state-descriptor"
      {:status (state-value env :display-type)
       :current-stimulus (state-value env :current-stimulus)
       :time (state-value env :current-time)
       :trial (- n-trials (state-value env :remaining-trials))}))

  (close [env] (reset! state {}))

  ;; no randomness
  (seed [env seed-value] nil))

;; the argument must be a map that includes the following keys
;; :n-trials -- the number of trials to run
(defn configure [env-args]
  (->Stroop (:n-trials env-args)
            (or (:weights env-args) trial-type)
            (or (:color-onset env-args) color-onset)
            (atom (new-state (:n-trials env-args) (:task env-args)))))
