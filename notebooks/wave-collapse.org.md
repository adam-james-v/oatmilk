# 🌊 Wave Function Collapse

## Concepts

The wave function collapse algorithm is pretty awesome, and is well
documented online. I heard about it first from Martin Donald\'s
excellent video *[Superpositions, Sudoku, the Wave Function Collapse
algorithm.](https://www.youtube.com/watch?v=2SuvO4Gi7uY)*

This is my first attempt at an implementation for the WFC algorithm, so
I don\'t expect things to be perfect, but I have enough understanding to
give it a real go. Here are the basic concepts that matter:

1.  **Modules:** A module is a *renderable* thing. In this example, a
    module is some neat looking SVG element, but if you search around
    you\'ll probably encounter examples where each module is a pixel-art
    *tile* for a 2D game, or even a 3D mesh. Each module will have rules
    about which modules can be adjacent to it. There are several ways in
    which these rules can be defined and enforced, but the general term
    to describe them is *constraints*. I will be using sockets to define
    valid adjacencies.

2.  **Sockets:** A socket is a way to mark up a module\'s connection
    points to help determine the valid neighbours for that module. In my
    implementation, I will use integers to indicate the socket type. A
    single line at the center of an edge is a `1` socket, an edge facing
    an \'exterior\' is `0`, and an \'interior\' is `2`. It\'s important
    for my implementation to not get too caught up in specific
    meaningfulness of each socket number, because I think the socket
    system can be auotomated much more in a follow-on implementation.
    It\'s also possible to add more socket types, for example, if I had
    a set of modules with two lines at an edge, I would define a new
    socket number.

3.  **Grid and Cells:** The grid is an `[n-cols n-rows]` table of cells,
    each of which houses the superpositions of modules. It is this grid
    that gets run through the WFC algorithm and eventually is fully
    collapsed into a renderable result. A collapsed grid is just a grid
    where every cell has no more superposition.

4.  **Entropy, Superposition, Collapse:** A superposition of modules is
    representable by a list of all valid modules for a given cell, and a
    cell\'s entropy is some measure indicating the \'level of
    superposition\', roughly speaking. A higher entropy indicates more
    uncertainty about the state of the cell. In my simple
    implmementation, I calculate entropy of a cell as the count of the
    possible modules for that cell. So, the lowest possible entropy
    should be 1, which corresponds to a fully collapsed cell. A
    completely collapsed grid is when each cell has minimum entropy.

5.  **Propagation:** The goal for a given grid is to fully collapse it.
    But if a grid starts with maximum entropy in each cell, there has to
    be some way to start the process of collapse, and to calculate the
    consequences of a cell\'s reduction in entropy. This is what
    *propagation* does. Given some low entropy cell, or a random cell
    from a set of equally low entropy cells, it may be possible to
    reduce the entropy of its neighbouring cells by comparing the
    possible sockets in the cell to the existing sockets in the
    superpositions of each neighbour, and filtering out all modules that
    can no longer connect. Then, each neighbour might be able to lower
    the entropies of *their* neighbours, and so on until no entropy
    changes can occur for that iteration of the propagation. To start
    another iteration, a new low-entropy cell is chosen, and is fully
    collapsed by randomly selecting one module from its superposition of
    possible modules. Propagation continues in this way until the grid
    is fully collapsed.

**NOTE:** There are sets of modules/sockets that can cause this
algorithm to fail. My first implementation is a bit naive and doesn\'t
fully consider every failure mode. I suspect I\'ll need to build a more
robust socket/constraint system in general, but for this note, I\'m
happy with how things are working.

With some of the high level stuff under control, I\'m ready to build
something in Clojure!

## ns

As is common with my notes and experiments, I want to use my SVG
library. I\'ll also be using `clojure.set` to help with filtering
modules based on their sockets.

``` clojure
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
```

## Tile Functions

Let\'s design a set of tiles that we can use to build our modules. A
tile will be a function that returns some tileable SVG element. The
function will take 0 arguments and either always return the same
element, or some random variation of the elements. I\'m also going to
define a few top-level vars to control some aspects of each tile like
the styling and size. I\'m going to restrict tiles to being square, too.

I\'d love to have variable dimension cells and modules, but that\'s
beyond the scope of this note.

``` clojure
(def tile-size 16)

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

(clerk/html (svg (render-tiles [corner side inner])))
```

### weird-tiles :no-export:

``` clojure
(defn tile-a
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 1.0)]
                      [(* tile-size 0.5) (* tile-size 0.5)]
                      [(* tile-size 1.0) (* tile-size 0.5)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/circle (/ tile-size 6.0))
        tile-style)))

(defn tile-b
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.0) (* tile-size 0.5)]
                      [(* tile-size 0.25) (* tile-size 0.5)]
                      [(* tile-size 0.5) (* tile-size 0.25)]
                      [(* tile-size 0.75) (* tile-size 0.5)]
                      [(* tile-size 1.0) (* tile-size 0.5)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/rect (/ tile-size 6.0) (/ tile-size 6.0))
        (tf/rotate 45)
        tile-style)))

(defn tile-c
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.0) (* tile-size 0.5)]
                      [(* tile-size 0.25) (* tile-size 0.5)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.75)]
                      [(* tile-size 0.5) (* tile-size 1.0)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/circle (/ tile-size 10.0))
        tile-style)
    (-> (el/circle (/ tile-size 4.0))
        tile-style)))

(defn tile-d
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.0)]
                      [(* tile-size 0.5) (* tile-size 0.25)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.75)]
                      [(* tile-size 0.5) (* tile-size 1.0)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/rect (/ tile-size 4.0) (/ tile-size 4.0))
        tile-style)))

(defn tile-e
  []
  (el/g
    base-tile
    (-> (el/circle (/ tile-size 64.0))
        tile-style)
    (-> (el/circle (/ tile-size 32.0))
        tile-style)))

(defn tile-f
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.0)]
                      [(* tile-size 0.5) (* tile-size 0.25)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.75)]
                      [(* tile-size 0.5) (* tile-size 1.0)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polygon (p/regular-polygon-pts (/ tile-size 4.0) 6))
        (tf/rotate 30)
        tile-style)))

(defn tile-g
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.0)]
                      [(* tile-size 0.25) (* tile-size 0.25)]
                      [(* tile-size 0.75) (* tile-size 0.25)]
                      [(* tile-size 0.75) (* tile-size 0.75)]
                      [(* tile-size 1.0) (* tile-size 0.5)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/rect (/ tile-size 4.0) (/ tile-size 4.0))
        tile-style)))

(defn tile-h
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.25) (* tile-size 0.375)]
                      [(* tile-size 0.0) (* tile-size 0.5)]
                      [(* tile-size 0.25) (* tile-size 0.625)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polyline [[(* tile-size 0.75) (* tile-size 0.375)]
                      [(* tile-size 1.0) (* tile-size 0.5)]
                      [(* tile-size 0.75) (* tile-size 0.625)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/rect (/ tile-size 2.0) (/ tile-size 4.0))
        tile-style)))

(defn tile-i
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.0)]
                      [(* tile-size 0.5) (* tile-size 0.25)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polyline [[(* tile-size 0.0) (* tile-size 0.5)]
                      [(* tile-size 0.25) (* tile-size 0.5)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/rect (/ tile-size 4.0) (/ tile-size 4.0))
        tile-style)
    (-> (el/rect (/ tile-size 2.0) (/ tile-size 2.0))
        tile-style)))

(defn tile-1
  []
  (el/g
    base-tile
    (-> (el/polyline [[(* tile-size 0.0) (* tile-size 0.5)]
                      [(* tile-size 1.0) (* tile-size 0.5)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)
    (-> (el/polyline [[(* tile-size 0.5) (* tile-size 0.0)]
                      [(* tile-size 0.5) (* tile-size 1.0)]])
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
        tile-style)))

(def tiles [tile-a tile-b tile-c
            tile-d tile-1 tile-f
            tile-g tile-h tile-i])
```

## Modules

Modules are implemented as a map with two keys: `[:sockets :tile]`,
where the sockets are always a vector of 4 sockets indicating the socket
type at the north, east, south, and west edge of the module. The tile
key holds the function that returns the tile graphic.

WIP -\> Keep writing here

``` clojure
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
    (mapcat #(module-rotations % 4)
            [{:sockets [0 1 1 0] :tile corner}
             {:sockets [0 1 1 2] :tile corner}
             {:sockets [2 1 1 2] :tile corner}
             {:sockets [2 1 1 0] :tile corner}
             {:sockets [0 1 0 1] :tile side}
             {:sockets [0 1 2 1] :tile side}
             {:sockets [2 1 2 1] :tile side}
             {:sockets [2 1 0 1] :tile side}])
    (repeat 8 {:sockets [2 2 2 2] :tile (fn [] base-tile)})
    (repeat 1 {:sockets [0 0 0 0] :tile inner})))

(clerk/html (svg (render-modules basic-module-set)))
```

## Grid

The grid I\'ll represent with a map whose keys are `[col row]` tuples.
The values for each cell in the grid are the superpositions: lists of
possible modules for that cell.

``` clojure
(defn neighbour-keys
  [[col row]]
  (let [ks [[col (dec row)]
            [(inc col) row]
            [col (inc row)]
            [(dec col) row]]]
    ks))

(defn initial-grid
  [ncols nrows module-set]
  (zipmap (p/rect-grid ncols nrows 1 1) (repeat module-set)))

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
                  (and (= 2 (count entropies)) (boolean (get entropies 1)))
                  (get entropies 1)

                  ;; some cells have greater than minimum possible entropy, pick those
                  :else (get entropies (apply min (keys (dissoc entropies 1)))))
        [k _] (rand-nth choices)]
    (if (< le 1)
      (println (str "Entropy Too low -> Propagation Error perhaps? Entropy: " le))
      k)))

```

## Collapse

``` clojure
(defn collapse-one-at
  [pos gridmap]
  (update gridmap pos #(vector (rand-nth %))))

(defn collapse-one
  [gridmap]
  (collapse-one-at (lowest-entropy-pos gridmap) gridmap))

(defn update-neighbours
  [pos gridmap module-set]
  (let [neighbours (neighbour-keys pos)
        socket-sets (zipmap neighbours (valid-sockets-for-neighbours pos gridmap  module-set))
        new-neighbours (for [k neighbours]
                         (let [modules (get gridmap k)]
                           (when modules
                             (let [valid-modules (filter #((get socket-sets k) (:sockets %)) modules)]
                               [k (if (> (count valid-modules) 0) valid-modules [(last module-set)])]))))]
    (merge gridmap (into {} new-neighbours))))

(defn propagate
  [gridmap module-set]
  (let [break (atom 1000)
        seed (lowest-entropy-pos gridmap)
        stack (atom [seed])
        gm (atom (collapse-one-at seed gridmap))]
    (while (and (> (count @stack) 0) (> @break 0))
      (let [pos (first @stack)
            nks (neighbour-keys pos)
            old-neighbours (into {} (mapv #(vector % (get @gm %)) nks))
            new-gm (update-neighbours pos @gm module-set)]
        ;; pop position off the list
        (swap! stack rest)
        ;; filter invalid neighbouring tile options
        (reset! gm new-gm)
        ;; add adjusted positions to the stack
        (doseq [k nks]
          (when (not= (set (get new-gm k)) (set (get old-neighbours k)))
            (swap! stack conj k)))
        (swap! break dec)))
    @gm))

(defn collapsed?
  [gridmap]
  (= #{1} (set (map count (vals gridmap)))))

(defn collapse
  [gridmap module-set i]
  (if (or (collapsed? gridmap) (< i 1))
    gridmap
    (let [gridmap (-> gridmap
                      (propagate module-set))]
      (recur gridmap module-set (dec i)))))

(defn generate
  [ncols nrows module-set]
  (collapse (initial-grid ncols nrows module-set) module-set 10000))
```

## Rendering

``` clojure
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
  (el/g
    (for [[pos modules] gridmap]
      (let [{:keys [sockets tile]} (first modules)]
        (-> (tile)
            #_(el/g (render-sockets sockets))
            (tf/translate (utils/v* [tile-size tile-size] pos)))))))

(def result (svg (render-gridmap (generate 32 32 basic-module-set))))
(clerk/html result)
```

## Adjusting Module Sets

There\'s a lot of tweaking we can do with the module sets to get
different results from the generator.

If we reduce the total number of modules that have certain types of
sockets, we can start to see generation that has much bigger zones of
inner/outer tiles. This is where the power and the artistry really comes
in, I think. There\'s a lot of design potential in not only the tile
functions but also in the module sets and sockets.

``` clojure
(def module-set-2
  (concat
    (mapcat #(module-rotations % 4)
            [{:sockets [0 1 1 0] :tile corner}
             {:sockets [0 1 1 2] :tile corner}
             #_{:sockets [2 1 1 2] :tile corner}
             #_{:sockets [2 1 1 0] :tile corner}
             {:sockets [0 1 0 1] :tile side}
             {:sockets [0 1 2 1] :tile side}
             #_{:sockets [2 1 2 1] :tile side}
             #_{:sockets [2 1 0 1] :tile side}])
    (repeat 8 {:sockets [2 2 2 2] :tile (fn [] base-tile)})
    (repeat 4 {:sockets [0 0 0 0] :tile inner})))

(def result2 (svg (render-gridmap (generate 32 32 module-set-2))))
(clerk/html result2)
```
