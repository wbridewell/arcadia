(ns
  ^{:doc "Provides an environment for receiving screenshots from and sending commands to Unity.
          The commands can be used to control an entity in the Unity environment,
          get information about the state of the Unity environment,
          and to pause, unpause, or advance a single frame inside Unity."}
  arcadia.simulator.environment.unityenv
  (:require [aleph.tcp :as tcp]
            [aleph.netty :as netty]
            [arcadia.simulator.environment.core :refer [Environment close]]
            [arcadia.utility [image :as img] [opencv :as cv]]
            [gloss.core :as gloss]
            [gloss.io :as gio]
            [manifold.stream :as ms]
            [manifold.deferred :as md])
  (:import [javax.swing JFrame]))

(def server-port 0)
(def client-port 8887)
(def screen-server (atom nil))
(def client (atom nil))

(def dscreen-data (atom nil))
(def current-screen (atom nil))

(def height 600)
(def width 800)

(def cameras #{"WindshieldCamera" "SideviewMirrorRightCamera" "SideviewMirrorLeftCamera" "RearviewMirrorCamera" "SpeedometerCamera"})
(def actions #{"look" "turn" "gas"})


(def ^:private jframe (atom nil))
(def ^:private location
  {:xpos (Math/floor (/ width 2))
   :ypos (Math/floor (/ height 2))
   :xcorner 0
   :ycorner 0})

(def command-protocol
  (gloss/string :ascii))

(def screen-protocol
  (gloss/finite-block :int32))

;(defn echo-watch
  ; [key identity old new])
  ;(println key old "=>" new))

;(add-watch dscreen-data :echo echo-watch)

(defn wrap-duplex-stream
  [protocol s]
  (let [out (ms/stream)]
    (ms/connect
      (ms/map #(gio/encode command-protocol %) out)
      s)
    (ms/splice
      out
      (gio/decode-stream s command-protocol))))

(defn command-client
  [host port]
  (md/chain (tcp/client {:host host :port port})
    #(wrap-duplex-stream command-protocol %)))

(defn send-request [client msg]
  (ms/put! client (str msg "\n"))
  (let [c (ms/take! client)]
    ;(md/on-realized c
  ;    #(println "C Success" (type %))
;      #(println "C Failure" (type %))
    @c))


(defn send-unity-command
  [msg]
  (println "Command: " msg)
  (println "Response:" (send-request @client msg)))

(defn wrap-screen-stream
  [s]
  (ms/splice
   s
   (gio/decode-stream s screen-protocol)))

(defn start-screen-server [port handler]
  (tcp/start-server
   (fn [s info]
     (handler (wrap-screen-stream s) info))
   {:port port}))

(defn screen-handler [s info]
  (println info)
  (println "Changing dscreen-data")
  (reset! dscreen-data (ms/take! s)))

;; takes the deferred stream that contains the screen image and returns a
;; deferred that contains the buffered image representation of the screen.
(defn read-deferred-image [d]
  #_{:clj-kondo/ignore [:unresolved-var]}
  (md/chain d
            gio/to-byte-buffer
            #(.array %)
            #(java.io.ByteArrayInputStream. %)
            #(javax.imageio.ImageIO/read %)))

(defn close-sockets []
  (.close @screen-server)
  (.close @client))

(defn request-screen [screen-name]
  (when @screen-server
    (.close @screen-server))
  (reset! screen-server (start-screen-server server-port screen-handler))
  (send-unity-command (str "PORT 127.0.0.1:" (netty/port @screen-server)))
  (send-unity-command (str "SCREEN " screen-name)))

(defrecord UnityEnv []
  Environment

  ;;relay information about the environment
  (info [env]
        {:dimensions [:height :width]
         :height height
         :width width
         :render-modes ["human" "buffered-image" "opencv-matrix"]})

  ;;each call advances the Unity environment 1 frame.
  ;;The current environmental screenshot is updated
  ;;state information is updated
  (step [env action]
        ;;send message to advance Unity environment by 1 frame
        ;;confirm receipt of new Unity screenshot
        ;;confirm receipt of new Unity state information
        ;;(theater/advance-frames (:camera env) 1)
        ;; (reset! dscreen-data (md/success-deferred nil))
        ;(reset! dscreen-data nil)
        ;(while (or (not @dscreen-data ) (not @@dscreen-data))

        (request-screen "WindshieldCamera")
        (when @dscreen-data
          (reset! current-screen @(read-deferred-image @dscreen-data)))
        (send-unity-command "ADVANCE")
        {:done? false})

  (reset [env] (close env) ())
         ;;send command to Unity for reset
         ;;confirm reset

  ;; display the latest screenshot in a JFrame
  (render [env mode close]
    (println "RENDERING")
    (when @current-screen
      (case mode
        "human"
        (img/display-image! (or @jframe (reset! jframe (JFrame. "Unity Environment")))
          @current-screen)
        "buffered-image"
        {:image @current-screen
         :location location}
        "opencv-matrix"
        {:image (img/bufferedimage-to-mat
                 @current-screen
                 cv/CV_8UC3)
         :location location})))


  (close [env]
         (send-unity-command "SETAXIS H 0")
         (send-unity-command "SETAXIS V 0")
         ;(send-unity-command "UNPAUSE")
         (.close @screen-server)
         (.close @client))



  ;; no randomness
  (seed [env seed-value] nil))

;; arguments must be a map that includes the path to a video file
;; {:video-path "path"}
(defn configure [env-args]
  (reset! client @(command-client "localhost" client-port))
  (send-unity-command "PAUSE")
  (send-unity-command "ADVANCE")
  (send-unity-command "SETAXIS V 1.0")
  (println "setup is happening::::")
  (->UnityEnv))
