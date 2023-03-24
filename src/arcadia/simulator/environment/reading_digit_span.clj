(ns
 ^{:doc "Provides an environment for the reading digit span task. The task is
         to memorize letters interspersed with digits to be vocalized, shown on
         a rudimentary display. The timing can be adjusted as needed."}
 arcadia.simulator.environment.reading-digit-span
  (:require [arcadia.simulator.environment.core :refer [Environment]]
            [arcadia.utility [image :as img] [opencv :as cv]]
            [clojure.math.numeric-tower :as math]
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
  {:xpos (math/floor (/ width 2))
   :ypos (math/floor (/ height 2))
   :xcorner 0
   :ycorner 0})

(def background-color Color/WHITE)
(def foreground-color Color/BLACK)

(defn- char-range
  "Create a vector of characters ranging from start to end"
  [start end]
  (mapv (comp str char) (range (int start) (inc (int end)))))

;; cycle time will be treated as 25 milliseconds.
;; maybe this should be an ARCADIA-wide parameter determined by
;; the particular environment.
(def cycle-time 25)
(def excluded #{"A" "E" "I" "O" "U" "D" "Q"})
(def letters (remove excluded (char-range \A \Z)))
(def digits (mapv str (range 1 10)))
;; the unitary syllable used in the concurrent articulation condition
(def conc-art-syllable "ba")

(def presented-letters (atom []))
(def presented-digits (atom []))
(def responses (atom []))

(def recall-cue "+")

(defn- digit->lexeme
  "Produces the correct word representation for a digit. If the string
   is not a digit (as is the case in the conc. art. condition),
   return the string as is."
  [digit]
  (case digit
    "0" "zero"
    "1" "one"
    "2" "two"
    "3" "three"
    "4" "four"
    "5" "five"
    "6" "six"
    "7" "seven"
    "8" "eight"
    "9" "nine"
    digit))

(defn correct-responses?
  "Returns true if the model vocalized all
   presented digits and letters in order, false otherwise."
  [focus content]
  (let [expected (concat (mapv digit->lexeme @presented-digits)
                         @presented-letters)]
    (println :correct-responses? "EXPECTED: " expected)
    (println :correct-responses? "RESPONSES: " @responses)
    (= @responses expected)))

(defn correct-serial-positions?
  "Returns a seq of (0,1) values, where each value v_i corresponds
   to whether the model correctly vocalized the letter at serial position i."
  [focus content]
  (let [expected @presented-letters
        actual (drop (count @presented-digits) @responses)]
    (println :correct-responses? "EXPECTED: " expected)
    (println :correct-responses? "RESPONSES: " actual)
    (map #(if (= %1 %2) 1 0) actual expected)))


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

(defn- new-state [intertrial-interval n-letters n-digits]
  {:display-type :intertrial
   :remaining-time intertrial-interval
   :previous-digit nil
   :previous-letter nil
   :current-digit nil
   :current-letter nil
   :remaining-letters n-letters
   :remaining-digits n-digits})

(defn- draw-screen [env]
  (case (state-value env :display-type)
    :intertrial blank-screen
    :intratrial blank-screen
    :letter (draw-centered-text (state-value env :current-letter))
    :digit (draw-centered-text (state-value env :current-digit))
    :recall (draw-centered-text recall-cue)))

;; state
;;   display type: letter, digit, intertrial, intratrial
;;   remaining time: number of milliseconds left in the state
;;   previous digit: one of the digits (to avoid repetition)
;;   previous letter: one of the letters (to control repetition)
;;   current digit
;;   current letter
;;   remaining letters: number of letters left

;; uses vars in the namespace: height, width, letters, digits.
(defrecord ReadingDigitSpan [n-letters n-digits letter-interval digit-interval
                             intratrial-interval intertrial-interval
                             conc-art? state]
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
         :letters letters
         :digits digits
         :task-parameters {:total-letters n-letters
                           :letter-interval letter-interval
                           :digit-interval digit-interval
                           :letter-digit-interval intratrial-interval
                           :intertrial-interval intertrial-interval
                           :conc-art? conc-art?}})

  ;; intertrial -> letter -> [intratrial <-> digit] -> intertrial
  ;; returns [observation reward done? diagnostic-info]
  ;;
  ;; as an action, expects either an empty sequence or a sequence
  ;; containing one of the available actions for the environment.
  (step [env action]
        (when (seq action)
          (swap! responses concat action))

        ;; if trial number is zero, then no change (possibly return a done indicator).
        (cond
          (and (= (state-value env :display-type) :recall)
               (<= (state-value env :remaining-time) 0))
          {:reward 0 :done? true}

          ;; if change
          ;;   update display type based on state counter, reset state counter if necessary
          (<= (state-value env :remaining-time) 0)
          (do
            ;; if switching from intertrial to letter, pick a letter uniformly at random
            (cond
              (and (= (state-value env :display-type) :intertrial)
                   (zero? (state-value env :remaining-letters)))
              (swap! (:state env) assoc
                     :display-type :recall
                     :current-letter nil
                     :previous-letter (state-value env :current-letter)
                     :current-digit nil
                     :previous-digit (state-value env :current-digit)
                     :remaining-time (:intertrial-interval env))

              (= (state-value env :display-type) :intertrial)
              (let [new-letter (dgen/rand-nth (remove (set @presented-letters) letters))]
                (swap! (:state env) assoc
                       :display-type :letter
                       :current-letter new-letter
                       :previous-letter (state-value env :current-letter)
                       :remaining-time (:letter-interval env))
                (swap! presented-letters conj new-letter))
                ;; switching from letter to intratrial
              (= (state-value env :display-type) :letter)
              (swap! (:state env) assoc
                     :display-type :intratrial
                     :remaining-time (:intratrial-interval env))

              ;; if switching from intratrial to digit, pick a digit uniformly at random
              ;; (pick "ba" for the conc-art condition)
              (= (state-value env :display-type) :intratrial)
              (let [new-digit (if (:conc-art? env) conc-art-syllable
                                (dgen/rand-nth (remove #{(last @presented-digits)} digits)))]
                (swap! (:state env) assoc
                       :display-type :digit
                       :current-digit new-digit
                       :previous-digit (state-value env :current-digit)
                       :remaining-digits (dec (state-value env :remaining-digits))
                       :remaining-time (:digit-interval env))
                (swap! presented-digits conj new-digit))

              ;; if switching from digit to intertrial, decrement letter number
              ;; and reset the digit number
              (and (= (state-value env :display-type) :digit)
                   (zero? (state-value env :remaining-digits)))
              (swap! (:state env) assoc
                     :display-type :intertrial
                     :remaining-digits (:n-digits env)
                     :remaining-letters (dec (state-value env :remaining-letters))
                     :remaining-time (:intertrial-interval env))

              ;; switching from digit back to intratrial, decrement digit number
              :else
              (swap! (:state env) assoc
                     :display-type :intratrial
                     ;:remaining-digits (dec (state-value env :remaining-digits))
                     :remaining-time (:intratrial-interval env)))
            {:observation @(:state env) :reward 0 :done? false})

          ;; else update state counter if no change
          :else
          (do (swap! (:state env) assoc :remaining-time (- (state-value env :remaining-time) cycle-time))
            {:observation @(:state env) :reward 0 :done? false})))

  (reset [env]
         (reset! state (new-state (:intertrial-interval env) (:n-letters env) (:n-digits env)))
         (reset! responses [])
         (reset! presented-digits [])
         (reset! presented-letters []))

  ;; Draw for human consumption or get a Java BufferedImage.
  ;; render returns its results in key-value pairs
  (render [env mode close]
          (case mode
            "human"
            (img/display-image! (if @jframe @jframe
                                    (reset! jframe
                                            (doto (JFrame. "Reading Digit Span Environment")
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
                                                      :digit :digit-interval
                                                      :letter :letter-interval} (state-value env :display-type)))]

              {:status (state-value env :display-type)
               :current-letter (state-value env :current-letter)
               :current-digit (state-value env :current-digit)
               :time (- stage-time (state-value env :remaining-time)) ;; maybe we want this to be the remaining time, but unclear
               :trial (- n-letters (state-value env :remaining-letters))})))

  (close [env] (reset! state {}))

  ;; no randomness
  (seed [env seed-value] nil))

;; the argument must be a map that includes the following keys
;; :n-letters -- the number of letters to memorize
;; :n-digits -- the number of digits to display
;; :letter-interval -- the length of time to display the letter
;; :digit-interval -- the length of time to display the digit
;; :intratrial-interval -- the length of time to display a blank screen between digits
;; :intertrial-interval -- the length of time to display a blank screen at the beginning of each trial
;;
;; if the :conc-art? key is set to true, then a constant syllable "ba"
;; will be displayed in place of the digit
(defn configure [env-args]
  (reset! responses [])
  (reset! presented-digits [])
  (reset! presented-letters [])
  (->ReadingDigitSpan (:n-letters env-args)
                      (:n-digits env-args)
                      (:letter-interval env-args)
                      (:digit-interval env-args)
                      (:intratrial-interval env-args)
                      (:intertrial-interval env-args)
                      (:conc-art? env-args)
                      (atom (new-state (:intertrial-interval env-args)
                                       (:n-letters env-args)
                                       (:n-digits env-args)))))
