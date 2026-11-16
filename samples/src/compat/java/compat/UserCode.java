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
    return log;
  }
}
