package com.onthegomap.flatmap.examples;

import com.onthegomap.flatmap.FeatureCollector;
import com.onthegomap.flatmap.FlatMapRunner;
import com.onthegomap.flatmap.Profile;
import com.onthegomap.flatmap.config.Arguments;
import com.onthegomap.flatmap.reader.SourceFeature;
import com.onthegomap.flatmap.util.ZoomFunction;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a map of toilets from OpenStreetMap nodes tagged with
 * <a href="https://wiki.openstreetmap.org/wiki/Tag:amenity%3Dtoilets">amenity=toilets</a>.
 * <p>
 * To run this example:
 * <ol>
 *   <li>Download a .osm.pbf extract (see <a href="https://download.geofabrik.de/">Geofabrik download site</a></li>
 *   <li>then build the examples: {@code mvn -DskipTests=true --projects flatmap-examples -am clean package}</li>
 *   <li>then run this example: {@code java -cp flatmap-examples/target/flatmap-examples-*-fatjar.jar com.onthegomap.flatmap.examples.ToiletsOverlay osm="path/to/data.osm.pbf" mbtiles="data/output.mbtiles"}</li>
 *   <li>then run the demo tileserver: {@code ./scripts/serve-tiles-docker.sh}</li>
 *   <li>and view the output at <a href="http://localhost:8080">localhost:8080</a></li>
 * </ol>
 */
public class ToiletsOverlay implements Profile {

  /*
   * Assign every toilet a monotonically increasing ID so that we can limit output at low zoom levels to only the
   * highest ID toilet nodes. Be sure to use thread-safe data structures any time a profile holds state since multiple
   * threads invoke processFeature concurrently.
   */
  AtomicInteger toiletNumber = new AtomicInteger(0);

  @Override
  public void processFeature(SourceFeature sourceFeature, FeatureCollector features) {
    if (sourceFeature.hasTag("amenity", "toilets")) {
      features.centroid("toilets")
        .setZoomRange(0, 14)
        // to limit toilets displayed at lower zoom levels:
        // 1) set a z-order that defines a priority ordering of toilets. For mountains you might use "elevation"
        // but for toilets we just set it to the order in which we see them.
        .setZorder(toiletNumber.incrementAndGet())
        // 2) at lower zoom levels, divide each 256x256 px tile into 32x32 px squares and in each square only include
        // the toilets with the highest z-order within that square
        .setLabelGridSizeAndLimit(
          12, // only limit at z12 and below
          32, // break the tile up into 32x32 px squares
          4 // any only keep the 4 nodes with highest z-order in each 32px square
        )
        // and also whenever you set a label grid size limit, make sure you increase the buffer size so no
        // label grid squares will be the consistent between adjacent tiles
        .setBufferPixelOverrides(ZoomFunction.maxZoom(12, 32));
    }
  }

  /*
   * Hooks to override metadata values in the output mbtiles file. Only name is required, the rest are optional. Bounds,
   * center, minzoom, maxzoom are set automatically based on input data and flatmap config.
   *
   * See: https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md#metadata)
   */

  @Override
  public String name() {
    return "Toilets Overlay";
  }

  @Override
  public String description() {
    return "An example overlay showing toilets";
  }

  @Override
  public boolean isOverlay() {
    return true;
  }

  /*
   * Any time you use OpenStreetMap data, you must ensure clients display the following copyright. Most clients will
   * display this automatically if you populate it in the attribution metadata in the mbtiles file:
   */
  @Override
  public String attribution() {
    return """
      <a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap contributors</a>
      """.trim();
  }

  /*
   * Main entrypoint for the example program
   */
  public static void main(String[] args) throws Exception {
    run(Arguments.fromArgsOrConfigFile(args));
  }

  static void run(Arguments args) throws Exception {
    // FlatMapRunner is a convenience wrapper around the lower-level API for the most common use-cases.
    // See ToiletsOverlayLowLevelApi for an example using this same profile but the lower-level API
    FlatMapRunner.createWithArguments(args)
      .setProfile(new ToiletsOverlay())
      // override this default with osm="path/to/data.osm.pbf"
      .addOsmSource("osm", Path.of("data", "sources", "north-america_us_massachusetts.pbf"))
      // override this default with mbtiles="path/to/output.mbtiles"
      .overwriteOutput("mbtiles", Path.of("data", "toilets.mbtiles"))
      .run();
  }
}
