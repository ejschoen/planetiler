package com.onthegomap.flatmap;

import static com.onthegomap.flatmap.TestUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.onthegomap.flatmap.collections.FeatureGroup;
import com.onthegomap.flatmap.collections.FeatureSort;
import com.onthegomap.flatmap.collections.LongLongMap;
import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.geo.TileCoord;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.profiles.OpenMapTilesProfile;
import com.onthegomap.flatmap.read.OpenStreetMapReader;
import com.onthegomap.flatmap.read.OsmSource;
import com.onthegomap.flatmap.read.Reader;
import com.onthegomap.flatmap.read.ReaderFeature;
import com.onthegomap.flatmap.worker.Topology;
import com.onthegomap.flatmap.write.Mbtiles;
import com.onthegomap.flatmap.write.MbtilesWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.io.InputStreamInStream;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

/**
 * In-memory tests with fake data and profiles to ensure all features work end-to-end.
 */
public class FlatMapTest {

  private static final String TEST_PROFILE_NAME = "test name";
  private static final String TEST_PROFILE_DESCRIPTION = "test description";
  private static final String TEST_PROFILE_ATTRIBUTION = "test attribution";
  private static final String TEST_PROFILE_VERSION = "test version";
  private static final int Z14_TILES = 1 << 14;
  private static final double Z14_WIDTH = 1d / Z14_TILES;
  private static final int Z13_TILES = 1 << 13;
  private static final double Z13_WIDTH = 1d / Z13_TILES;
  private static final int Z12_TILES = 1 << 12;
  private static final double Z12_WIDTH = 1d / Z12_TILES;
  private static final int Z4_TILES = 1 << 4;
  private final Stats stats = new Stats.InMemory();

  private void processReaderFeatures(FeatureGroup featureGroup, Profile profile, CommonParams config,
    List<? extends SourceFeature> features) {
    new Reader(profile, stats, "test") {

      @Override
      public long getCount() {
        return features.size();
      }

      @Override
      public Topology.SourceStep<SourceFeature> read() {
        return features::forEach;
      }

      @Override
      public void close() {
      }
    }.process(featureGroup, config);
  }

  private void processOsmFeatures(FeatureGroup featureGroup, Profile profile, CommonParams config,
    List<? extends ReaderElement> osmElements) throws IOException {
    OsmSource elems = threads -> next -> {
      // process the same order they come in from an OSM file
      osmElements.stream().filter(e -> e.getType() == ReaderElement.FILEHEADER).forEachOrdered(next);
      osmElements.stream().filter(e -> e.getType() == ReaderElement.NODE).forEachOrdered(next);
      osmElements.stream().filter(e -> e.getType() == ReaderElement.WAY).forEachOrdered(next);
      osmElements.stream().filter(e -> e.getType() == ReaderElement.RELATION).forEachOrdered(next);
    };
    var nodeMap = LongLongMap.newInMemorySortedTable();
    try (var reader = new OpenStreetMapReader(elems, nodeMap, profile, new Stats.InMemory())) {
      reader.pass1(config);
      reader.pass2(featureGroup, config);
    }
  }

  private interface Runner {

    void run(FeatureGroup featureGroup, Profile profile, CommonParams config) throws Exception;
  }

  private FlatMapResults run(
    Map<String, String> args,
    Runner runner,
    BiConsumer<SourceFeature, FeatureCollector> profileFunction
  ) throws Exception {
    CommonParams config = CommonParams.from(Arguments.of(args));
    var translations = Translations.defaultProvider(List.of());
    var profile1 = new OpenMapTilesProfile();
    LongLongMap nodeLocations = LongLongMap.newInMemorySortedTable();
    FeatureSort featureDb = FeatureSort.newInMemory();
    FeatureGroup featureGroup = new FeatureGroup(featureDb, profile1, stats);
    var profile = TestProfile.processSourceFeatures(profileFunction);
    runner.run(featureGroup, profile, config);
    featureGroup.sorter().sort();
    try (Mbtiles db = Mbtiles.newInMemoryDatabase()) {
      MbtilesWriter.writeOutput(featureGroup, db, () -> 0L, profile, config, stats);
      var tileMap = TestUtils.getTileMap(db);
      tileMap.values().forEach(fs -> {
        fs.forEach(f -> f.geometry().validate());
      });
      return new FlatMapResults(tileMap, db.metadata().getAll());
    }
  }

  private FlatMapResults runWithReaderFeatures(
    Map<String, String> args,
    List<ReaderFeature> features,
    BiConsumer<SourceFeature, FeatureCollector> profileFunction
  ) throws Exception {
    return run(
      args,
      (featureGroup, profile, config) -> processReaderFeatures(featureGroup, profile, config, features),
      profileFunction
    );
  }

  private FlatMapResults runWithOsmElements(
    Map<String, String> args,
    List<ReaderElement> features,
    BiConsumer<SourceFeature, FeatureCollector> profileFunction
  ) throws Exception {
    return run(
      args,
      (featureGroup, profile, config) -> processOsmFeatures(featureGroup, profile, config, features),
      profileFunction
    );
  }

  @Test
  public void testMetadataButNoPoints() throws Exception {
    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(),
      (sourceFeature, features) -> {
      }
    );
    assertEquals(Map.of(), results.tiles);
    assertSubmap(Map.of(
      "name", TEST_PROFILE_NAME,
      "description", TEST_PROFILE_DESCRIPTION,
      "attribution", TEST_PROFILE_ATTRIBUTION,
      "version", TEST_PROFILE_VERSION,
      "type", "baselayer",
      "format", "pbf",
      "minzoom", "0",
      "maxzoom", "14",
      "center", "0,0,0",
      "bounds", "-180,-85.05113,180,85.05113"
    ), results.metadata);
    assertSameJson(
      """
        {
          "vector_layers": [
          ]
        }
        """,
      results.metadata.get("json")
    );
  }

  @Test
  public void testSinglePoint() throws Exception {
    double x = 0.5 + Z14_WIDTH / 2;
    double y = 0.5 + Z14_WIDTH / 2;
    double lat = GeoUtils.getWorldLat(y);
    double lng = GeoUtils.getWorldLon(x);

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newPoint(lng, lat), Map.of(
          "attr", "value"
        ))
      ),
      (in, features) -> {
        features.point("layer")
          .setZoomRange(13, 14)
          .setAttr("name", "name value")
          .inheritFromSource("attr");
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(Z14_TILES / 2, Z14_TILES / 2, 14), List.of(
        feature(newPoint(128, 128), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      ),
      TileCoord.ofXYZ(Z13_TILES / 2, Z13_TILES / 2, 13), List.of(
        feature(newPoint(64, 64), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      )
    ), results.tiles);
    assertSameJson(
      """
        {
          "vector_layers": [
            {"id": "layer", "fields": {"name": "String", "attr": "String"}, "minzoom": 13, "maxzoom": 14}
          ]
        }
        """,
      results.metadata.get("json")
    );
  }

  @Test
  public void testMultiPoint() throws Exception {
    double x1 = 0.5 + Z14_WIDTH / 2;
    double y1 = 0.5 + Z14_WIDTH / 2;
    double x2 = x1 + Z13_WIDTH / 256d;
    double y2 = y1 + Z13_WIDTH / 256d;
    double lat1 = GeoUtils.getWorldLat(y1);
    double lng1 = GeoUtils.getWorldLon(x1);
    double lat2 = GeoUtils.getWorldLat(y2);
    double lng2 = GeoUtils.getWorldLon(x2);

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newMultiPoint(
          newPoint(lng1, lat1),
          newPoint(lng2, lat2)
        ), Map.of(
          "attr", "value"
        ))
      ),
      (in, features) -> {
        features.point("layer")
          .setZoomRange(13, 14)
          .setAttr("name", "name value")
          .inheritFromSource("attr");
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(Z14_TILES / 2, Z14_TILES / 2, 14), List.of(
        feature(newMultiPoint(
          newPoint(128, 128),
          newPoint(130, 130)
        ), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      ),
      TileCoord.ofXYZ(Z13_TILES / 2, Z13_TILES / 2, 13), List.of(
        feature(newMultiPoint(
          newPoint(64, 64),
          newPoint(65, 65)
        ), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      )
    ), results.tiles);
  }

  @Test
  public void testLabelGridLimit() throws Exception {
    double y = 0.5 + Z14_WIDTH / 2;
    double lat = GeoUtils.getWorldLat(y);

    double x1 = 0.5 + Z14_WIDTH / 4;
    double lng1 = GeoUtils.getWorldLon(x1);
    double lng2 = GeoUtils.getWorldLon(x1 + Z14_WIDTH * 10d / 256);
    double lng3 = GeoUtils.getWorldLon(x1 + Z14_WIDTH * 20d / 256);

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newPoint(lng1, lat), Map.of("rank", "1")),
        new ReaderFeature(newPoint(lng2, lat), Map.of("rank", "2")),
        new ReaderFeature(newPoint(lng3, lat), Map.of("rank", "3"))
      ),
      (in, features) -> {
        features.point("layer")
          .setZoomRange(13, 14)
          .inheritFromSource("rank")
          .setZorder(Integer.parseInt(in.getTag("rank").toString()))
          .setLabelGridSizeAndLimit(13, 128, 2);
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(Z14_TILES / 2, Z14_TILES / 2, 14), List.of(
        feature(newPoint(64, 128), Map.of("rank", "1")),
        feature(newPoint(74, 128), Map.of("rank", "2")),
        feature(newPoint(84, 128), Map.of("rank", "3"))
      ),
      TileCoord.ofXYZ(Z13_TILES / 2, Z13_TILES / 2, 13), List.of(
        // omit rank=1 due to label grid size
        feature(newPoint(37, 64), Map.of("rank", "2")),
        feature(newPoint(42, 64), Map.of("rank", "3"))
      )
    ), results.tiles);
  }

  @Test
  public void testLineString() throws Exception {
    double x1 = 0.5 + Z14_WIDTH / 2;
    double y1 = 0.5 + Z14_WIDTH / 2;
    double x2 = x1 + Z14_WIDTH;
    double y2 = y1 + Z14_WIDTH;
    double lat1 = GeoUtils.getWorldLat(y1);
    double lng1 = GeoUtils.getWorldLon(x1);
    double lat2 = GeoUtils.getWorldLat(y2);
    double lng2 = GeoUtils.getWorldLon(x2);

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newLineString(lng1, lat1, lng2, lat2), Map.of(
          "attr", "value"
        ))
      ),
      (in, features) -> {
        features.line("layer")
          .setZoomRange(13, 14)
          .setBufferPixels(4);
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(Z14_TILES / 2, Z14_TILES / 2, 14), List.of(
        feature(newLineString(128, 128, 260, 260), Map.of())
      ),
      TileCoord.ofXYZ(Z14_TILES / 2 + 1, Z14_TILES / 2 + 1, 14), List.of(
        feature(newLineString(-4, -4, 128, 128), Map.of())
      ),
      TileCoord.ofXYZ(Z13_TILES / 2, Z13_TILES / 2, 13), List.of(
        feature(newLineString(64, 64, 192, 192), Map.of())
      )
    ), results.tiles);
  }

  @Test
  public void testMultiLineString() throws Exception {
    double x1 = 0.5 + Z14_WIDTH / 2;
    double y1 = 0.5 + Z14_WIDTH / 2;
    double x2 = x1 + Z14_WIDTH;
    double y2 = y1 + Z14_WIDTH;
    double lat1 = GeoUtils.getWorldLat(y1);
    double lng1 = GeoUtils.getWorldLon(x1);
    double lat2 = GeoUtils.getWorldLat(y2);
    double lng2 = GeoUtils.getWorldLon(x2);

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newMultiLineString(
          newLineString(lng1, lat1, lng2, lat2),
          newLineString(lng2, lat2, lng1, lat1)
        ), Map.of(
          "attr", "value"
        ))
      ),
      (in, features) -> {
        features.line("layer")
          .setZoomRange(13, 14)
          .setBufferPixels(4);
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(Z14_TILES / 2, Z14_TILES / 2, 14), List.of(
        feature(newMultiLineString(
          newLineString(128, 128, 260, 260),
          newLineString(260, 260, 128, 128)
        ), Map.of())
      ),
      TileCoord.ofXYZ(Z14_TILES / 2 + 1, Z14_TILES / 2 + 1, 14), List.of(
        feature(newMultiLineString(
          newLineString(-4, -4, 128, 128),
          newLineString(128, 128, -4, -4)
        ), Map.of())
      ),
      TileCoord.ofXYZ(Z13_TILES / 2, Z13_TILES / 2, 13), List.of(
        feature(newMultiLineString(
          newLineString(64, 64, 192, 192),
          newLineString(192, 192, 64, 64)
        ), Map.of())
      )
    ), results.tiles);
  }

  public List<Coordinate> z14CoordinateList(double... coords) {
    List<Coordinate> points = newCoordinateList(coords);
    points.forEach(c -> {
      c.x = GeoUtils.getWorldLon(0.5 + c.x * Z14_WIDTH);
      c.y = GeoUtils.getWorldLat(0.5 + c.y * Z14_WIDTH);
    });
    return points;
  }

  @Test
  public void testPolygonWithHoleSpanningMultipleTiles() throws Exception {
    List<Coordinate> outerPoints = z14CoordinateList(
      0.5, 0.5,
      3.5, 0.5,
      3.5, 2.5,
      0.5, 2.5,
      0.5, 0.5
    );
    List<Coordinate> innerPoints = z14CoordinateList(
      1.25, 1.25,
      1.75, 1.25,
      1.75, 1.75,
      1.25, 1.75,
      1.25, 1.25
    );

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newPolygon(
          outerPoints,
          List.of(innerPoints)
        ), Map.of())
      ),
      (in, features) -> {
        features.polygon("layer")
          .setZoomRange(12, 14)
          .setBufferPixels(4);
      }
    );

    assertEquals(Map.ofEntries(
      // Z12
      newTileEntry(Z12_TILES / 2, Z12_TILES / 2, 12, List.of(
        feature(newPolygon(
          rectangleCoordList(32, 32, 256 - 32, 128 + 32),
          List.of(
            rectangleCoordList(64 + 16, 128 - 16) // hole
          )
        ), Map.of())
      )),

      // Z13
      newTileEntry(Z13_TILES / 2, Z13_TILES / 2, 13, List.of(
        feature(newPolygon(
          rectangleCoordList(64, 256 + 4),
          List.of(rectangleCoordList(128 + 32, 256 - 32)) // hole
        ), Map.of())
      )),
      newTileEntry(Z13_TILES / 2 + 1, Z13_TILES / 2, 13, List.of(
        feature(rectangle(-4, 64, 256 - 64, 256 + 4), Map.of())
      )),
      newTileEntry(Z13_TILES / 2, Z13_TILES / 2 + 1, 13, List.of(
        feature(rectangle(64, -4, 256 + 4, 64), Map.of())
      )),
      newTileEntry(Z13_TILES / 2 + 1, Z13_TILES / 2 + 1, 13, List.of(
        feature(rectangle(-4, -4, 256 - 64, 64), Map.of())
      )),

      // Z14 - row 1
      newTileEntry(Z14_TILES / 2, Z14_TILES / 2, 14, List.of(
        feature(tileBottomRight(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 1, Z14_TILES / 2, 14, List.of(
        feature(tileBottom(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 2, Z14_TILES / 2, 14, List.of(
        feature(tileBottom(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 3, Z14_TILES / 2, 14, List.of(
        feature(tileBottomLeft(4), Map.of())
      )),
      // Z14 - row 2
      newTileEntry(Z14_TILES / 2, Z14_TILES / 2 + 1, 14, List.of(
        feature(tileRight(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 1, Z14_TILES / 2 + 1, 14, List.of(
        feature(newPolygon(
          tileFill(4),
          List.of(newCoordinateList(
            64, 64,
            192, 64,
            192, 192,
            64, 192,
            64, 64
          ))
        ), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 2, Z14_TILES / 2 + 1, 14, List.of(
        feature(newPolygon(tileFill(5), List.of()), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 3, Z14_TILES / 2 + 1, 14, List.of(
        feature(tileLeft(4), Map.of())
      )),
      // Z14 - row 3
      newTileEntry(Z14_TILES / 2, Z14_TILES / 2 + 2, 14, List.of(
        feature(tileTopRight(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 1, Z14_TILES / 2 + 2, 14, List.of(
        feature(tileTop(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 2, Z14_TILES / 2 + 2, 14, List.of(
        feature(tileTop(4), Map.of())
      )),
      newTileEntry(Z14_TILES / 2 + 3, Z14_TILES / 2 + 2, 14, List.of(
        feature(tileTopLeft(4), Map.of())
      ))
    ), results.tiles);
  }

  @Test
  public void testFullWorldPolygon() throws Exception {
    List<Coordinate> outerPoints = worldCoordinateList(
      Z14_WIDTH / 2, Z14_WIDTH / 2,
      1 - Z14_WIDTH / 2, Z14_WIDTH / 2,
      1 - Z14_WIDTH / 2, 1 - Z14_WIDTH / 2,
      Z14_WIDTH / 2, 1 - Z14_WIDTH / 2,
      Z14_WIDTH / 2, Z14_WIDTH / 2
    );

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newPolygon(
          outerPoints,
          List.of()
        ), Map.of())
      ),
      (in, features) -> {
        features.polygon("layer")
          .setZoomRange(0, 6)
          .setBufferPixels(4);
      }
    );

    assertEquals(5461, results.tiles.size());
    // spot-check one filled tile
    assertEquals(List.of(rectangle(-5, 256 + 5).norm()), results.tiles.get(TileCoord.ofXYZ(
      Z4_TILES / 2, Z4_TILES / 2, 4
    )).stream().map(d -> d.geometry().geom().norm()).toList());
  }

  @ParameterizedTest
  @CsvSource({
    "chesapeake.wkb, 4076",
    "mdshore.wkb,    19904",
    "njshore.wkb,    10571"
  })
  public void testComplexShorelinePolygons__TAKES_A_MINUTE_OR_TWO(String fileName, int expected)
    throws Exception, ParseException {
    MultiPolygon geometry = (MultiPolygon) new WKBReader()
      .read(new InputStreamInStream(Files.newInputStream(Path.of("src", "test", "resources", fileName))));
    assertNotNull(geometry);

    // automatically checks for self-intersections
    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(geometry, Map.of())
      ),
      (in, features) -> {
        features.polygon("layer")
          .setZoomRange(0, 14)
          .setBufferPixels(4);
      }
    );

    assertEquals(expected, results.tiles.size());
  }

  @Test
  public void testReorderNestedMultipolygons() throws Exception {
    List<Coordinate> outerPoints1 = worldRectangle(10d / 256, 240d / 256);
    List<Coordinate> innerPoints1 = worldRectangle(20d / 256, 230d / 256);
    List<Coordinate> outerPoints2 = worldRectangle(30d / 256, 220d / 256);
    List<Coordinate> innerPoints2 = worldRectangle(40d / 256, 210d / 256);

    var results = runWithReaderFeatures(
      Map.of("threads", "1"),
      List.of(
        new ReaderFeature(newMultiPolygon(
          newPolygon(outerPoints2, List.of(innerPoints2)),
          newPolygon(outerPoints1, List.of(innerPoints1))
        ), Map.of())
      ),
      (in, features) -> {
        features.polygon("layer")
          .setZoomRange(0, 0)
          .setBufferPixels(0);
      }
    );

    var tileContents = results.tiles.get(TileCoord.ofXYZ(0, 0, 0));
    assertEquals(1, tileContents.size());
    Geometry geom = tileContents.get(0).geometry().geom();
    assertTrue(geom instanceof MultiPolygon, geom.toString());
    MultiPolygon multiPolygon = (MultiPolygon) geom;
    assertSameNormalizedFeature(newPolygon(
      rectangleCoordList(10, 240),
      List.of(rectangleCoordList(20, 230))
    ), multiPolygon.getGeometryN(0));
    assertSameNormalizedFeature(newPolygon(
      rectangleCoordList(30, 220),
      List.of(rectangleCoordList(40, 210))
    ), multiPolygon.getGeometryN(1));
    assertEquals(2, multiPolygon.getNumGeometries());
  }

  @Test
  public void testOsmPoint() throws Exception {
    var results = runWithOsmElements(
      Map.of("threads", "1"),
      List.of(
        with(new ReaderNode(1, 0, 0), t -> t.setTag("attr", "value"))
      ),
      (in, features) -> {
        if (in.isPoint()) {
          features.point("layer")
            .setZoomRange(0, 0)
            .setAttr("name", "name value")
            .inheritFromSource("attr");
        }
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(0, 0, 0), List.of(
        feature(newPoint(128, 128), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      )
    ), results.tiles);
  }

  private static <T extends ReaderElement> T with(T elem, Consumer<T> fn) {
    fn.accept(elem);
    return elem;
  }

  @Test
  public void testOsmLine() throws Exception {
    var results = runWithOsmElements(
      Map.of("threads", "1"),
      List.of(
        new ReaderNode(1, 0, 0),
        new ReaderNode(2, GeoUtils.getWorldLat(0.75), GeoUtils.getWorldLon(0.75)),
        with(new ReaderWay(3), way -> {
          way.setTag("attr", "value");
          way.getNodes().add(1, 2);
        })
      ),
      (in, features) -> {
        if (in.canBeLine()) {
          features.line("layer")
            .setZoomRange(0, 0)
            .setAttr("name", "name value")
            .inheritFromSource("attr");
        }
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(0, 0, 0), List.of(
        feature(newLineString(128, 128, 192, 192), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      )
    ), results.tiles);
  }

  @Test
  public void testOsmLineOrPolygon() throws Exception {
    var results = runWithOsmElements(
      Map.of("threads", "1"),
      List.of(
        new ReaderNode(1, GeoUtils.getWorldLat(0.25), GeoUtils.getWorldLon(0.25)),
        new ReaderNode(2, GeoUtils.getWorldLat(0.25), GeoUtils.getWorldLon(0.75)),
        new ReaderNode(3, GeoUtils.getWorldLat(0.75), GeoUtils.getWorldLon(0.75)),
        new ReaderNode(4, GeoUtils.getWorldLat(0.75), GeoUtils.getWorldLon(0.25)),
        with(new ReaderWay(6), way -> {
          way.setTag("attr", "value");
          way.getNodes().add(1, 2, 3, 4, 1);
        })
      ),
      (in, features) -> {
        if (in.canBeLine()) {
          features.line("layer")
            .setZoomRange(0, 0)
            .setAttr("name", "name value1")
            .inheritFromSource("attr");
        }
        if (in.canBePolygon()) {
          features.polygon("layer")
            .setZoomRange(0, 0)
            .setAttr("name", "name value2")
            .inheritFromSource("attr");
        }
      }
    );

    assertSubmap(sortListValues(Map.of(
      TileCoord.ofXYZ(0, 0, 0), List.of(
        feature(newLineString(
          64, 64,
          192, 64,
          192, 192,
          64, 192,
          64, 64
        ), Map.of(
          "attr", "value",
          "name", "name value1"
        )),
        feature(rectangle(64, 192), Map.of(
          "attr", "value",
          "name", "name value2"
        ))
      )
    )), sortListValues(results.tiles));
  }

  private <K extends Comparable<? super K>, V extends List<?>> Map<K, ?> sortListValues(Map<K, V> input) {
    Map<K, List<?>> result = new TreeMap<>();
    for (var entry : input.entrySet()) {
      List<?> sorted = entry.getValue().stream().sorted(Comparator.comparing(Object::toString)).toList();
      result.put(entry.getKey(), sorted);
    }
    return result;
  }

  @Test
  public void testOsmMultipolygon() throws Exception {
    var results = runWithOsmElements(
      Map.of("threads", "1"),
      List.of(
        new ReaderNode(1, GeoUtils.getWorldLat(0.125), GeoUtils.getWorldLon(0.125)),
        new ReaderNode(2, GeoUtils.getWorldLat(0.125), GeoUtils.getWorldLon(0.875)),
        new ReaderNode(3, GeoUtils.getWorldLat(0.875), GeoUtils.getWorldLon(0.875)),
        new ReaderNode(4, GeoUtils.getWorldLat(0.875), GeoUtils.getWorldLon(0.125)),

        new ReaderNode(5, GeoUtils.getWorldLat(0.25), GeoUtils.getWorldLon(0.25)),
        new ReaderNode(6, GeoUtils.getWorldLat(0.25), GeoUtils.getWorldLon(0.75)),
        new ReaderNode(7, GeoUtils.getWorldLat(0.75), GeoUtils.getWorldLon(0.75)),
        new ReaderNode(8, GeoUtils.getWorldLat(0.75), GeoUtils.getWorldLon(0.25)),

        new ReaderNode(9, GeoUtils.getWorldLat(0.375), GeoUtils.getWorldLon(0.375)),
        new ReaderNode(10, GeoUtils.getWorldLat(0.375), GeoUtils.getWorldLon(0.625)),
        new ReaderNode(11, GeoUtils.getWorldLat(0.625), GeoUtils.getWorldLon(0.625)),
        new ReaderNode(12, GeoUtils.getWorldLat(0.625), GeoUtils.getWorldLon(0.375)),
        new ReaderNode(13, GeoUtils.getWorldLat(0.375 + 1e-12), GeoUtils.getWorldLon(0.375)),

        with(new ReaderWay(14), way -> way.getNodes().add(1, 2, 3, 4, 1)),
        with(new ReaderWay(15), way -> way.getNodes().add(5, 6, 7, 8, 5)),
        with(new ReaderWay(16), way -> way.getNodes().add(9, 10, 11, 12, 13)),

        with(new ReaderRelation(17), rel -> {
          rel.setTag("type", "multipolygon");
          rel.setTag("attr", "value");
          rel.setTag("should_emit", "yes");
          rel.add(new ReaderRelation.Member(ReaderRelation.Member.WAY, 14, "outer"));
          rel.add(new ReaderRelation.Member(ReaderRelation.Member.WAY, 15, "inner"));
          rel.add(new ReaderRelation.Member(ReaderRelation.Member.WAY, 16, "inner")); // incorrect
        })
      ),
      (in, features) -> {
        if (in.hasTag("should_emit")) {
          features.polygon("layer")
            .setZoomRange(0, 0)
            .setAttr("name", "name value")
            .inheritFromSource("attr");
        }
      }
    );

    assertSubmap(Map.of(
      TileCoord.ofXYZ(0, 0, 0), List.of(
        feature(newMultiPolygon(
          newPolygon(
            rectangleCoordList(0.125 * 256, 0.875 * 256),
            List.of(
              rectangleCoordList(0.25 * 256, 0.75 * 256)
            )
          ),
          rectangle(0.375 * 256, 0.625 * 256)
        ), Map.of(
          "attr", "value",
          "name", "name value"
        ))
      )
    ), results.tiles);
  }

  private Map.Entry<TileCoord, List<TestUtils.ComparableFeature>> newTileEntry(int x, int y, int z,
    List<TestUtils.ComparableFeature> features) {
    return Map.entry(TileCoord.ofXYZ(x, y, z), features);
  }

  private interface LayerPostprocessFunction {

    List<VectorTileEncoder.Feature> process(String layer, int zoom, List<VectorTileEncoder.Feature> items);
  }

  private static record FlatMapResults(
    Map<TileCoord, List<TestUtils.ComparableFeature>> tiles, Map<String, String> metadata
  ) {}

  private static record TestProfile(
    @Override String name,
    @Override String description,
    @Override String attribution,
    @Override String version,
    BiConsumer<SourceFeature, FeatureCollector> processFeature,
    Function<ReaderRelation, List<OpenStreetMapReader.RelationInfo>> preprocessOsmRelation,
    LayerPostprocessFunction postprocessLayerFeatures
  ) implements Profile {

    TestProfile(
      BiConsumer<SourceFeature, FeatureCollector> processFeature,
      Function<ReaderRelation, List<OpenStreetMapReader.RelationInfo>> preprocessOsmRelation,
      LayerPostprocessFunction postprocessLayerFeatures
    ) {
      this(TEST_PROFILE_NAME, TEST_PROFILE_DESCRIPTION, TEST_PROFILE_ATTRIBUTION, TEST_PROFILE_VERSION, processFeature,
        preprocessOsmRelation,
        postprocessLayerFeatures);
    }

    static TestProfile processSourceFeatures(BiConsumer<SourceFeature, FeatureCollector> processFeature) {
      return new TestProfile(processFeature, (a) -> null, (a, b, c) -> null);
    }

    @Override
    public List<OpenStreetMapReader.RelationInfo> preprocessOsmRelation(
      ReaderRelation relation) {
      return preprocessOsmRelation.apply(relation);
    }

    @Override
    public void processFeature(SourceFeature sourceFeature, FeatureCollector features) {
      processFeature.accept(sourceFeature, features);
    }

    @Override
    public void release() {
    }

    @Override
    public List<VectorTileEncoder.Feature> postProcessLayerFeatures(String layer, int zoom,
      List<VectorTileEncoder.Feature> items) {
      return postprocessLayerFeatures.process(layer, zoom, items);
    }
  }
}
