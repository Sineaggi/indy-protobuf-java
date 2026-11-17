package compat.@V@;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import @PKG@sample.Address;
import @PKG@sample.Kind;
import @PKG@sample.Person;
import @PKG@sample.PersonOrBuilder;
import @PKG@sample.legacy.Legacy;
import @PKG@sample.legacy.Legacy.Event;
import @PKG@shapes.Point;
import @PKG@shapes.Shape;
import @PKG@shapes.ShapeOrBuilder;
import @PKG@shapes.Shapes2;
import @PKG@shapes.Unit;
import @PKG@shapes.ext.Ext2;
import @PKG@shapes.full.Full2;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.UninitializedMessageException;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Existing user code": written once against the protoc-generated API, compiled twice (against
 * the protoc classes in package sample.* and the indy classes in indy.sample.*). It exercises the
 * API surface javac sees: generics, nested Builder types, checked exceptions, enums in switches,
 * nested builders, OrBuilder views and the static helpers.
 */
public final class UserCode {
  private UserCode() {}

  public static List<String> run() throws IOException {
    List<String> log = new ArrayList<>();

    Person.Builder b = Person.newBuilder()
        .setName("Grace")
        .setId(7L)
        .setAge(85)
        .addAllTags(List.of("navy", "cobol"))
        .addScores(3).addAllScores(List.of(1, 4, 1, 5))
        .setKind(Kind.KIND_A)
        .addKinds(Kind.KIND_B).addKindsValue(77)
        .setBlob(ByteString.copyFromUtf8("bytes"))
        .addChunks(ByteString.copyFromUtf8("c1"))
        .setNickname("amazing")
        .setPinned(Person.Note.newBuilder().setText("pinned").setKind(Kind.KIND_B));
    b.getAddressBuilder().setStreet("Arlington").setZip(22201).addLines("line 1");
    b.addPreviousBuilder().setStreet("NYC");
    b.addPrevious(Address.newBuilder().setStreet("Philadelphia").build());
    b.getPreviousBuilder(0).setZip(10001);
    b.addNotesBuilder().setText("n1");
    Person p = b.build();

    // reuse the builder after build(): earlier message must not change
    b.addTags("later").getPreviousBuilder(1).setZip(19104);
    Person p2 = b.build();
    log.add("p.tags=" + p.getTagsList() + " p2.tags=" + p2.getTagsList());
    log.add("p.prev1.zip=" + p.getPrevious(1).getZip() + " p2.prev1.zip=" + p2.getPrevious(1).getZip());

    PersonOrBuilder view = b;
    log.add("view.name=" + view.getName() + " hasAge=" + view.hasAge() + " address=" + view.getAddressOrBuilder().getStreet());

    for (Kind k : p.getKindsList()) {
      switch (k) {
        case KIND_A -> log.add("kind A");
        case KIND_B -> log.add("kind B");
        case UNRECOGNIZED -> log.add("kind unrecognized, raw=" + p.getKindsValue(1));
        default -> log.add("kind other");
      }
    }
    log.add("kindValues=" + p.getKindsValueList());

    byte[] bytes = p.toByteArray();
    try {
      Person back = Person.parseFrom(bytes);
      log.add("equal=" + back.equals(p) + " hash=" + (back.hashCode() == p.hashCode()));
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
    try {
      Person.parseFrom(new byte[] {(byte) 0x0a, (byte) 0xff});
      log.add("no exception?!");
    } catch (InvalidProtocolBufferException e) {
      log.add("truncated -> " + e.getClass().getSimpleName());
    }

    Parser<Person> parser = Person.parser();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    p.writeDelimitedTo(out);
    p2.writeDelimitedTo(out);
    ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
    log.add("delimited=" + parser.parseDelimitedFrom(in).getTagsCount() + "," + Person.parseDelimitedFrom(in).getTagsCount());

    Message.Builder generic = p.newBuilderForType();
    generic.mergeFrom(p);
    log.add("generic=" + generic.build().equals(p));

    Person cleared = p.toBuilder().clearAddress().clearTags().clearAge().clearPinned().build();
    log.add("cleared hasAddress=" + cleared.hasAddress() + " tags=" + cleared.getTagsCount() + " hasAge=" + cleared.hasAge());

    Event e = Event.newBuilder()
        .setSource("svc")
        .setLevel(Event.Level.WARN)
        .addSeen(Event.Level.DEBUG)
        .setWho(p)
        .setAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
        .addIds(1L).addIds(2L)
        .build();
    Legacy.Batch batch = Legacy.Batch.newBuilder().addEvents(e).addEvents(e.toBuilder().clearWho()).setName("b").build();
    log.add("batch events=" + batch.getEventsCount() + " who=" + batch.getEvents(0).getWho().getName()
        + " level=" + batch.getEvents(0).getLevel() + " at=" + batch.getEvents(0).getAt().getSeconds());
    log.add("event field numbers " + Event.SOURCE_FIELD_NUMBER + "," + Event.WHO_FIELD_NUMBER + " level " + Event.Level.WARN_VALUE);
    log.add("descriptor=" + Event.getDescriptor().getFullName() + " file=" + Legacy.getDescriptor().getName());
    log.add("json=" + JsonFormat.printer().omittingInsignificantWhitespace().print(batch.getEvents(1)));
    log.add("text=" + p.getPinned().toString().trim());
    shapes(log);
    proto2(log);
    return log;
  }

  private static void proto2(List<String> log) throws IOException {
    Full2.Full d = Full2.Full.getDefaultInstance();
    log.add("defaults n=" + d.getN() + " s=" + d.getS() + " b=" + d.getB().size() + " d=" + d.getD() + " f=" + d.getF()
        + " c=" + d.getC() + " t=" + d.getT() + " u=" + d.getU() + " neg=" + d.getNeg() + " nan=" + Double.isNaN(d.getNan())
        + " pi=" + d.getPi() + " ps=" + d.getPs() + " pc=" + d.getPc() + " hasN=" + d.hasN());
    log.add("aliases " + (Full2.Color.CRIMSON == Full2.Color.RED) + " " + Full2.Color.NAVY + " " + Full2.Color.NAVY_VALUE
        + " " + java.util.Arrays.toString(Full2.Color.values()));
    Full2.Full.Builder fb = Full2.Full.newBuilder().setN(1).setC(Full2.Color.CRIMSON);
    log.add("initialized=" + fb.isInitialized() + " errors=" + fb.findInitializationErrors());
    try {
      fb.build();
    } catch (UninitializedMessageException e) {
      log.add("build -> " + e.getMessage());
    }
    fb.getGBuilder().setGv(1);
    fb.setName("n")
        .addR(Full2.Full.R.newBuilder().setRv("r1")).addRBuilder().setRv("r2");
    log.add("missing greq: " + fb.findInitializationErrors());
    fb.getGBuilder().setGreq(2);
    fb.setPG(Full2.Full.PG.newBuilder().setPgv(9));
    Full2.Full full = fb.build();
    log.add("full g=" + full.getG().getGreq() + " r=" + full.getRCount() + " pick=" + full.getPickCase() + " pg=" + full.getPG().getPgv()
        + " c=" + full.getC());
    Full2.Full back = Full2.Full.parseFrom(full.toByteArray());
    log.add("group round trip=" + back.equals(full) + " cleared n=" + back.toBuilder().clearN().getN()
        + " cleared s=" + back.toBuilder().setS("x").clearS().getS());
    try {
      Full2.Full.parseFrom(Full2.Full.newBuilder().setN(3).buildPartial().toByteArray());
    } catch (InvalidProtocolBufferException e) {
      log.add("parse uninitialized -> " + e.getMessage() + " unfinished=" + e.getUnfinishedMessage().getSerializedSize());
    }

    Ext2.Base base = Ext2.Base.newBuilder()
        .setA(1)
        .setExtension(Ext2.eInt, 7)
        .addExtension(Ext2.eStrs, "x").addExtension(Ext2.eStrs, "y")
        .setExtension(Ext2.eMsg, Full2.Inner.newBuilder().setV(3).build())
        .setExtension(Ext2.eColor, Full2.Color.GREEN)
        .setExtension(Ext2.Scope.back, Ext2.Scope.newBuilder().setX(5).build())
        .addExtension(Ext2.Scope.colors, Full2.Color.BLUE)
        .setB("b")
        .build();
    log.add("ext int=" + base.getExtension(Ext2.eInt) + " strs=" + base.getExtension(Ext2.eStrs) + " count=" + base.getExtensionCount(Ext2.eStrs)
        + " msg=" + base.getExtension(Ext2.eMsg).getV() + " color=" + base.getExtension(Ext2.eColor)
        + " back=" + base.getExtension(Ext2.Scope.back).getX() + " has str=" + base.hasExtension(Ext2.eStr)
        + " str default=" + base.getExtension(Ext2.eStr) + " numbers " + Ext2.E_INT_FIELD_NUMBER + "," + Ext2.Scope.BACK_FIELD_NUMBER);
    ExtensionRegistry reg = ExtensionRegistry.newInstance();
    Ext2.registerAllExtensions(reg);
    Ext2.Base parsed = Ext2.Base.parseFrom(base.toByteArray(), reg);
    Ext2.Base noReg = Ext2.Base.parseFrom(base.toByteArray());
    log.add("ext parsed equal=" + parsed.equals(base) + " without registry=" + noReg.equals(base)
        + " unknown=" + noReg.getUnknownFields().asMap().keySet() + " bytes same=" + java.util.Arrays.equals(noReg.toByteArray(), base.toByteArray()));
    log.add("ext cleared=" + parsed.toBuilder().clearExtension(Ext2.eInt).build().hasExtension(Ext2.eInt)
        + " set index=" + parsed.toBuilder().setExtension(Ext2.eStrs, 1, "z").build().getExtension(Ext2.eStrs, 1));
    log.add("ext text=" + base.toString().replace('\n', ' '));
    Ext2.ReqExt r = Ext2.ReqExt.newBuilder().setExtension(Ext2.reqInner, Full2.Inner.newBuilder().buildPartial()).buildPartial();
    log.add("ext required initialized=" + r.isInitialized() + " " + r.findInitializationErrors());
  }

  private static void shapes(List<String> log) throws IOException {
    Shape.Builder sb = Shape.newBuilder().setName("sq").setRadius(2.5);
    log.add("case=" + sb.getKindCase() + " radius=" + sb.getRadius() + " hasCorner=" + sb.hasCorner());
    sb.getCornerBuilder().setX(3).setY(4);
    log.add("case=" + sb.getKindCase() + " radius=" + sb.getRadius() + " corner=" + sb.getCorner().getX()
        + "," + sb.getCornerOrBuilder().getY());
    sb.setUnitValue(9);
    log.add("unit=" + sb.getUnit() + " raw=" + sb.getUnitValue() + " case#=" + sb.getKindCase().getNumber());
    sb.setLabel("hi").setRatio(0.5f);
    Shape s1 = sb.build();
    switch (s1.getKindCase()) {
      case LABEL -> log.add("label " + s1.getLabel() + "/" + s1.getLabelBytes().size());
      case KIND_NOT_SET -> log.add("not set");
      default -> log.add("other " + s1.getKindCase());
    }
    log.add("second=" + s1.getSecondCase() + " forNumber=" + Shape.KindCase.forNumber(3) + " " + Shape.SecondCase.valueOf("DELTA"));
    Shape s2 = s1.toBuilder().clearKind().setDelta(-3).build();
    log.add("s2 case=" + s2.getKindCase() + " label=[" + s2.getLabel() + "] delta=" + s2.getDelta() + " ratio=" + s2.getRatio());
    log.add("cleared member=" + s1.toBuilder().clearLabel().getKindCase() + " other member=" + s1.toBuilder().clearRadius().getKindCase());

    Shape.Builder mb = Shape.newBuilder()
        .putCounts("a", 1).putCounts("b", 2)
        .putAllCounts(Map.of("c", 3))
        .putNames(7, "seven")
        .putPoints("o", Point.newBuilder().setX(1).build())
        .putUnits(5L, Unit.INCH).putUnitsValue(6L, 42)
        .putBlobs(true, ByteString.copyFromUtf8("t"))
        .putWeights(1, 0.25)
        .putTimes("now", Timestamp.newBuilder().setSeconds(9).build());
    mb.putPointsBuilderIfAbsent("p").setY(8);
    mb.removeCounts("b");
    mb.getMutableCounts().put("d", 4);
    Shape m = mb.build();
    log.add("counts=" + new TreeMap<>(m.getCountsMap()) + " n=" + m.getCountsCount() + " hasA=" + m.containsCounts("a")
        + " z=" + m.getCountsOrDefault("z", -1) + " a=" + m.getCountsOrThrow("a"));
    try {
      m.getCountsOrThrow("zz");
    } catch (IllegalArgumentException ex) {
      log.add("orThrow -> IAE");
    }
    try {
      mb.putCounts(null, 1);
    } catch (NullPointerException ex) {
      log.add("null key -> NPE");
    }
    log.add("names=" + m.getNamesMap() + " points=" + new TreeMap<>(m.getPointsMap()).keySet() + " p.y=" + m.getPointsOrThrow("p").getY());
    log.add("units=" + new TreeMap<>(m.getUnitsMap()) + " values=" + new TreeMap<>(m.getUnitsValueMap())
        + " u6=" + m.getUnitsOrDefault(6L, Unit.MM) + " u6v=" + m.getUnitsValueOrThrow(6L));
    log.add("blobs=" + m.getBlobsMap().get(true).toStringUtf8() + " weights=" + m.getWeightsMap() + " times=" + m.getTimesOrThrow("now").getSeconds());
    ShapeOrBuilder view = mb;
    log.add("builder view counts=" + new TreeMap<>(view.getCountsMap()) + " points=" + view.getPointsCount());
    Shape back = Shape.parseFrom(m.toByteArray());
    log.add("maps equal=" + back.equals(m) + " hash=" + (back.hashCode() == m.hashCode()));
    log.add("merged counts=" + new TreeMap<>(m.toBuilder().mergeFrom(Shape.newBuilder().putCounts("a", 10).build()).build().getCountsMap()));
    log.add("cleared maps=" + m.toBuilder().clearCounts().clearPoints().build().getPointsCount());
    log.add("json=" + JsonFormat.printer().omittingInsignificantWhitespace().print(m.toBuilder().clearUnits().clearPoints().clearTimes()
        .clearCounts().clearBlobs().build()));

    Shapes2.Legacy l = Shapes2.Legacy.newBuilder()
        .setLevel(Shapes2.Level.HIGH)
        .putLevels("x", Shapes2.Level.LOW)
        .putKids(1L, Shapes2.Legacy.newBuilder().setText("kid").build())
        .setId(3)
        .build();
    log.add("legacy pick=" + l.getPickCase() + " level=" + l.getLevel() + " levels=" + l.getLevelsMap()
        + " kid=" + l.getKidsOrThrow(1L).getText() + " id=" + l.getId());
    Shapes2.Legacy l2 = Shapes2.Legacy.parseFrom(l.toBuilder().setAt(Point.newBuilder().setX(5)).build().toByteArray());
    log.add("legacy at=" + l2.getAt().getX() + " pick=" + l2.getPickCase() + " " + Shapes2.Legacy.PickCase.PICK_NOT_SET);
  }
}
