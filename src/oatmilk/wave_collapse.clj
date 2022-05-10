(ns oatmilk.wave-collapse
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [svg-clj.composites :refer [svg]]
            [svg-clj.elements :as el]
            [svg-clj.layout :as lo]
            [svg-clj.path :as path]
            [svg-clj.parametric :as p]
            [svg-clj.tools :as tools]
            [svg-clj.transforms :as tf]
            [svg-clj.utils :as utils]
            [nextjournal.clerk :as clerk]))

(def tile-size 9)

(defn tile-style
  [el]
  (-> el
      (tf/style {:fill "none"
                 :stroke "slategray"
                 :stroke-width 1})))

(def base-tile
  (-> (el/rect tile-size tile-size)
      (tf/style {:fill "none"})))

(defn corner
  []
  (let [shape (rand-nth [(el/polyline [[(* tile-size 0.5) (* tile-size 1.0)]
                                       [(* tile-size 0.5) (* tile-size 0.5)]
                                       [(* tile-size 1.0) (* tile-size 0.5)]])
                         (el/polyline [[(* tile-size 0.5) (* tile-size 1.0)]
                                       [(* tile-size 1.0) (* tile-size 0.5)]])
                         (path/bezier [(* tile-size 0.5) (* tile-size 1.0)]
                                      [(* tile-size 0.5) (* tile-size 0.5)]
                                      [(* tile-size 1.0) (* tile-size 0.5)])])]
    (el/g
      base-tile
      (-> shape
          (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
          tile-style))))

(defn side
  []
  (let [shape (rand-nth [(el/polyline [[(* tile-size 0.0) (* tile-size 0.5)]
                                       [(* tile-size 1.0) (* tile-size 0.5)]])
                         (path/bezier [(* tile-size 0.0) (* tile-size 0.5)]
                                      [(* tile-size 0.5) (* tile-size 0.125)]
                                      [(* tile-size 1.0) (* tile-size 0.5)])])]
    (el/g
      base-tile
      (-> shape
          (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
          tile-style))))

(defn inner
  []
  (el/g
    base-tile
    (-> (el/circle (* tile-size 0.375 (rand)))
        tile-style
        (tf/style {:opacity (rand)}))
    (-> (el/circle (* tile-size 0.375 (rand)))
        tile-style
        (tf/style {:opacity (rand)}))))

(defn render-tiles
  [tiles]
  (let [n-tiles (count tiles)
        ny (int (Math/sqrt n-tiles))
        nx (Math/ceil (/ n-tiles ny))
        grid (p/rect-grid nx ny tile-size tile-size)]
    (lo/distribute-on-pts (map #(%) tiles) grid)))

(defn rotate-module
  "Rotates a tile 90 degrees counter clockwise"
  [{:keys [sockets tile]}]
  {:sockets (vec (take 4 (drop 1 (cycle sockets))))
   :tile (fn [] (tf/rotate (tile) -90))})

(defn module-rotations
  [module n]
  (when (#{2 4} n)
    (let [[b c d a] (take 4 (iterate rotate-module module))]
      (case n
        2 [a c]
        4 [a b c d]
        nil))))

;; sockets follow [:north :east :south :west]
(def basic-module-set
  (concat
    (module-rotations {:sockets [0 1 1 0] :tile corner} 4)
    (module-rotations {:sockets [0 1 0 1] :tile side} 4)
    [{:sockets [0 0 0 0] :tile inner}]))

(def empty-module-set
  (concat
    (module-rotations {:sockets [0 1 1 0] :tile []} 4)
    (module-rotations {:sockets [0 1 0 1] :tile []} 4)
    [{:sockets [0 0 0 0] :tile []}]))

(defn neighbour-keys
  [[col row]]
  [[col (dec row)]
   [(inc col) row]
   [col (inc row)]
   [(dec col) row]])

(defn initial-grid
  [ncols nrows module-set]
  (zipmap (p/rect-grid ncols nrows 1 1) (repeat (vec module-set))))

(defn valid-sockets-for-module
  [{:keys [sockets]} module-set]
  (let [[n e s w] sockets
        all-sockets (set (map :sockets module-set))]
    [(set (filter #(= n (nth % 2)) all-sockets))
     (set (filter #(= e (nth % 3)) all-sockets))
     (set (filter #(= s (nth % 0)) all-sockets))
     (set (filter #(= w (nth % 1)) all-sockets))]))

(defn merge-socket-sets
  [socket-sets]
  (let [[ns es ss ws] (map (fn [n] (map #(get % n) socket-sets)) [0 1 2 3])]
    (mapv #(apply set/union %) [ns es ss ws])))

(defn valid-sockets-for-neighbours
  [pos gridmap module-set]
  (merge-socket-sets (map #(valid-sockets-for-module % module-set) (get gridmap pos))))

(defn lowest-entropy-pos
  [gridmap]
  (let [entropies (group-by second (update-vals gridmap count))
        [le he] (apply (juxt min max) (keys entropies))
        choices (cond
                  ;; all cells have the same entropy
                  (= 1 (count entropies))
                  (first (vals entropies))

                  ;; only one cell left un-collapsed
                  (and (= 2 (count entropies)) (= 1 (count (get entropies he))))
                  (get entropies he)

                  ;; cells either have full entropy or minimum possible entropy
                  (and (= 2 (count entropies)) (= 1 (count (get entropies 1))))
                  (get entropies he)

                  ;; some cells have greater than minimum possible entropy, pick those
                  :else (get entropies (apply min (keys (dissoc entropies 1 0)))))
        [k _] (rand-nth choices)]
    (if (< le 1)
      (println (str "Entropy Too low -> Propagation Error perhaps? Entropy: " le))
      k)))

(defn collapse-one-at
  [pos gridmap]
  (update gridmap pos #(vector (rand-nth %))))

(defn update-neighbours
  [pos gridmap module-set]
  (let [neighbours (neighbour-keys pos)
        socket-sets (zipmap neighbours (valid-sockets-for-neighbours pos gridmap module-set))
        new-neighbours (for [k neighbours]
                         (when-let [modules (get gridmap k)]
                           (let [valid-modules (filter #((get socket-sets k) (:sockets %)) modules)]
                             [k valid-modules])))]
    (into {} new-neighbours)))

(defn propagate
  [gridmap module-set]
  (let [seed (lowest-entropy-pos gridmap)]
    (loop [gm (collapse-one-at seed gridmap)
           stack [seed]]
      (if (< (count stack) 1)
        gm
        (let [[pos stack] ((juxt peek pop) stack)
              nks (neighbour-keys pos)
              old-neighbours (into {} (mapv #(vector % (get gm %)) nks))
              new-neighbours (update-neighbours pos gm module-set)
              new-stack (into stack (comp
                                      (filter #(not= (count (get new-neighbours %))
                                                     (count (get old-neighbours %))))
                                      (map conj)) nks)]
          (recur (merge gm new-neighbours) new-stack))))))

(defn collapsed?
  [gridmap]
  (let [counts (set (map count (vals gridmap)))]
    (or (= #{1} counts)
        (= #{0 1} counts))))

(defn collapse
  [gridmap module-set]
  (if (collapsed? gridmap)
    gridmap
    (let [gridmap (-> gridmap
                      (propagate module-set))]
      (recur (propagate gridmap module-set) module-set))))

(defn generate
  [ncols nrows module-set]
  (collapse (initial-grid ncols nrows module-set) module-set))

(defn render-sockets
  [[n e s w]]
  (let [cols {0 "red"
              1 "green"
              2 "blue"}]
    (-> (el/g
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.45) (* tile-size 0.05)])
              (tf/style {:fill (get cols n)}))
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.95) (* tile-size 0.45)])
              (tf/style {:fill (get cols e)}))
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.45) (* tile-size 0.95)])
              (tf/style {:fill (get cols s)}))
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.05) (* tile-size 0.45)])
              (tf/style {:fill (get cols w)})))
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)]))))

(defn render-gridmap
  [gridmap]
  (let [rfn (fn [[pos modules]]
              (let [{:keys [tile]} (first modules)]
                (-> (tile)
                    #_(el/g (render-sockets sockets))
                    (tf/translate (utils/v* [tile-size tile-size] pos)))))]
    (el/g (pmap rfn gridmap))))
