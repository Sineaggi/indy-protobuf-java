package dev.indyproto.runtime;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Compact per-message field table shared by the build-time generator (which encodes it into the
 * constant pool of each generated class) and the link-time bootstraps (which decode it and spin
 * code from it).
 *
 * <p>Encoding: fields separated by {@code ';'}, each field is
 * {@code number:kind:flags:bit:CamelName:typeInternalName}. Flags are a set of letters:
 * {@code r} repeated, {@code p} packed on write, {@code h} explicit presence, {@code u} validate
 * UTF-8, {@code c} closed enum. {@code bit} is the presence bit index or {@code -1}.
 */
public final class Schema {

  public enum Kind {
    INT32('I', "Int32", 0, ListKind.INT),
    INT64('J', "Int64", 0, ListKind.LONG),
    UINT32('I', "UInt32", 0, ListKind.INT),
    UINT64('J', "UInt64", 0, ListKind.LONG),
    SINT32('I', "SInt32", 0, ListKind.INT),
    SINT64('J', "SInt64", 0, ListKind.LONG),
    FIXED32('I', "Fixed32", 5, ListKind.INT),
    FIXED64('J', "Fixed64", 1, ListKind.LONG),
    SFIXED32('I', "SFixed32", 5, ListKind.INT),
    SFIXED64('J', "SFixed64", 1, ListKind.LONG),
    FLOAT('F', "Float", 5, ListKind.FLOAT),
    DOUBLE('D', "Double", 1, ListKind.DOUBLE),
    BOOL('Z', "Bool", 0, ListKind.BOOLEAN),
    ENUM('I', "Enum", 0, ListKind.INT),
    STRING('L', "String", 2, ListKind.STRING),
    BYTES('L', "Bytes", 2, ListKind.OBJECT),
    MESSAGE('L', "Message", 2, ListKind.OBJECT),
    /** A map field: one length-delimited MapEntry per element; see {@link Field#mapKey()}. */
    MAP('L', "Message", 2, ListKind.OBJECT);

    /** JVM primitive descriptor char of the storage, or 'L' for references. */
    public final char jvm;
    /** Suffix of the CodedInputStream / CodedOutputStream method names, e.g. {@code readInt32}. */
    public final String io;

    public final int wireType;
    public final ListKind list;

    Kind(char jvm, String io, int wireType, ListKind list) {
      this.jvm = jvm;
      this.io = io;
      this.wireType = wireType;
      this.list = list;
    }

    public boolean packable() {
      return jvm != 'L';
    }

    /** Descriptor of the value as read/written on the stream API. */
    public ClassDesc valueDesc() {
      return switch (jvm) {
        case 'I' -> CD.I;
        case 'J' -> CD.J;
        case 'F' -> CD.F;
        case 'D' -> CD.D;
        case 'Z' -> CD.Z;
        default -> throw new IllegalStateException(name());
      };
    }
  }

  /** The list implementation used for a repeated field. */
  public enum ListKind {
    INT("IntList", "Int"),
    LONG("LongList", "Long"),
    FLOAT("FloatList", "Float"),
    DOUBLE("DoubleList", "Double"),
    BOOLEAN("BooleanList", "Boolean"),
    STRING(null, null),
    OBJECT(null, null);

    public final String internalListName;
    public final String accessorSuffix;

    ListKind(String internalListName, String accessorSuffix) {
      this.internalListName = internalListName;
      this.accessorSuffix = accessorSuffix;
    }

    public ClassDesc desc() {
      return switch (this) {
        case STRING -> CD.LAZY_STRING_LIST;
        case OBJECT -> CD.LIST;
        default -> ClassDesc.of("com.google.protobuf.Internal$" + internalListName);
      };
    }
  }

  public record Field(
      int number,
      Kind kind,
      boolean repeated,
      boolean packed,
      boolean presence,
      boolean utf8,
      boolean closedEnum,
      int bit,
      String camelName,
      String typeInternalName,
      int oneof,
      Kind mapKey,
      Kind mapValue,
      boolean group,
      boolean customDefault) {

    /** {@link #wireKind()} of a group (a MESSAGE field with start/end-group framing). */
    public static final int GROUP_WIRE_KIND = Kind.values().length;

    public Field(int number, Kind kind, boolean repeated, boolean packed, boolean presence,
        boolean utf8, boolean closedEnum, int bit, String camelName, String typeInternalName,
        int oneof, Kind mapKey, Kind mapValue) {
      this(number, kind, repeated, packed, presence, utf8, closedEnum, bit, camelName,
          typeInternalName, oneof, mapKey, mapValue, false, false);
    }

    /** The kind's ordinal, or {@link #GROUP_WIRE_KIND} for groups (passed to Rt helpers). */
    public int wireKind() {
      return group ? GROUP_WIRE_KIND : kind.ordinal();
    }

    /** "Group" or "Message": the CodedOutputStream / CodedInputStream method infix. */
    public String messageIo() {
      return group ? "Group" : "Message";
    }

    public Field(int number, Kind kind, boolean repeated, boolean packed, boolean presence,
        boolean utf8, boolean closedEnum, int bit, String camelName, String typeInternalName) {
      this(number, kind, repeated, packed, presence, utf8, closedEnum, bit, camelName,
          typeInternalName, -1, null, null);
    }

    /** repeated bytes: an Internal.ProtobufList, as in protoc (message lists are plain Lists). */
    public boolean bytesList() {
      return repeated && kind == Kind.BYTES;
    }

    public boolean isMap() {
      return kind == Kind.MAP;
    }

    public boolean inOneof() {
      return oneof >= 0;
    }

    /** Name of the builder's private {@code internalGetMutableFoo()} for map fields. */
    public String mapMutableGetter() {
      return "internalGetMutable" + camelName;
    }

    /** Name of the message's private static accessor of the map's default MapEntry. */
    public String mapEntryMethod() {
      return "$" + Character.toLowerCase(camelName.charAt(0)) + camelName.substring(1) + "Entry";
    }

    /** Map values that are messages use a MapFieldBuilder on the builder side. */
    public boolean mapOfMessages() {
      return kind == Kind.MAP && mapValue == Kind.MESSAGE;
    }

    /** Name of the private storage field, matching protoc's {@code fooBar_}. */
    public String fieldName() {
      return Character.toLowerCase(camelName.charAt(0)) + camelName.substring(1) + "_";
    }

    public String builderFieldName() {
      return Character.toLowerCase(camelName.charAt(0)) + camelName.substring(1) + "Builder_";
    }

    public String memoFieldName() {
      return Character.toLowerCase(camelName.charAt(0)) + camelName.substring(1)
          + "MemoizedSerializedSize";
    }

    public ClassDesc typeDesc() {
      return typeInternalName == null ? null : ClassDesc.ofInternalName(typeInternalName);
    }

    /** JVM type of the storage field (same on message and builder, except maps of messages). */
    public ClassDesc storageDesc() {
      if (kind == Kind.MAP) return CD.MAP_FIELD;
      if (bytesList()) return CD.PROTOBUF_LIST;
      if (repeated) {
        return kind.list.desc();
      }
      return switch (kind) {
        case STRING -> CD.OBJECT;
        case BYTES -> CD.BYTE_STRING;
        case MESSAGE -> typeDesc();
        default -> kind.valueDesc();
      };
    }

    /** JVM type of the builder's storage field. */
    public ClassDesc builderStorageDesc() {
      return mapOfMessages() ? CD.MAP_FIELD_BUILDER : storageDesc();
    }

    public int tag() {
      return (number << 3) | (group ? 3 : kind.wireType);
    }

    public int packedTag() {
      return (number << 3) | 2;
    }

    public String encode() {
      StringBuilder f = new StringBuilder();
      if (repeated) f.append('r');
      if (packed) f.append('p');
      if (presence) f.append('h');
      if (utf8) f.append('u');
      if (closedEnum) f.append('c');
      if (group) f.append('g');
      if (customDefault) f.append('d');
      return number + ":" + kind.name() + ":" + f + ":" + bit + ":" + camelName + ":"
          + (typeInternalName == null ? "" : typeInternalName) + ":" + oneof + ":"
          + (mapKey == null ? "" : mapKey.name()) + ":" + (mapValue == null ? "" : mapValue.name());
    }

    static Field decode(String s) {
      String[] p = s.split(":", -1);
      String flags = p[2];
      return new Field(
          Integer.parseInt(p[0]),
          Kind.valueOf(p[1]),
          flags.indexOf('r') >= 0,
          flags.indexOf('p') >= 0,
          flags.indexOf('h') >= 0,
          flags.indexOf('u') >= 0,
          flags.indexOf('c') >= 0,
          Integer.parseInt(p[3]),
          p[4],
          p[5].isEmpty() ? null : p[5],
          Integer.parseInt(p[6]),
          p[7].isEmpty() ? null : Kind.valueOf(p[7]),
          p[8].isEmpty() ? null : Kind.valueOf(p[8]),
          flags.indexOf('g') >= 0,
          flags.indexOf('d') >= 0);
    }
  }

  /** An extension range, {@code end} exclusive (protoc's {@code writeUntil(end)} argument). */
  public record Range(int start, int end) {}

  private final List<Field> fields;
  private final List<Field> declared;
  private final List<String> oneofs;
  private final List<Range> ranges;

  public Schema(List<Field> fields) {
    this(fields, List.of());
  }

  public Schema(List<Field> fields, List<String> oneofs) {
    this(fields, oneofs, List.of());
  }

  /**
   * {@code fields} in declaration order; {@code oneofs} are the real oneofs' lowerCamel names;
   * {@code ranges} the extension ranges (non-empty: an ExtendableMessage).
   */
  public Schema(List<Field> fields, List<String> oneofs, List<Range> ranges) {
    List<Range> r = new ArrayList<>(ranges);
    r.sort(Comparator.comparingInt(Range::start));
    this.ranges = List.copyOf(r);
    this.declared = List.copyOf(fields);
    List<Field> sorted = new ArrayList<>(fields);
    sorted.sort(Comparator.comparingInt(Field::number));
    this.fields = Collections.unmodifiableList(sorted);
    this.oneofs = List.copyOf(oneofs);
  }

  /** Fields in field-number order (wire order). */
  public List<Field> fields() {
    return fields;
  }

  public List<Range> ranges() {
    return ranges;
  }

  public boolean extendable() {
    return !ranges.isEmpty();
  }

  /**
   * protoc's serialization order: fields by number, each extension range written (via the
   * ExtensionSerializer) just before the first field numbered at or after its start.
   * Elements are {@link Field}s and {@link Range}s.
   */
  public List<Object> wireOrder() {
    List<Object> out = new ArrayList<>();
    int i = 0, j = 0;
    while (i < fields.size() || j < ranges.size()) {
      if (i == fields.size()) out.add(ranges.get(j++));
      else if (j == ranges.size()) out.add(fields.get(i++));
      else if (fields.get(i).number() < ranges.get(j).start()) out.add(fields.get(i++));
      else out.add(ranges.get(j++));
    }
    return out;
  }

  /** Fields in declaration order (protoc's hashCode / equals order). */
  public List<Field> declared() {
    return declared;
  }

  public List<String> oneofs() {
    return oneofs;
  }

  /** Members of oneof {@code i}, in declaration order. */
  public List<Field> oneofMembers(int i) {
    List<Field> out = new ArrayList<>();
    for (Field f : declared) if (f.oneof() == i) out.add(f);
    return out;
  }

  public String oneofCaseField(int i) {
    return oneofs.get(i) + "Case_";
  }

  public String oneofValueField(int i) {
    return oneofs.get(i) + "_";
  }

  /** Number of {@code bitFieldN_} words needed for presence bits. */
  public int bitWords() {
    int max = -1;
    for (Field f : fields) max = Math.max(max, f.bit);
    return (max + 32) / 32;
  }

  public static String bitFieldName(int bit) {
    return "bitField" + (bit / 32) + "_";
  }

  public String encode() {
    StringBuilder sb = new StringBuilder(String.join(",", oneofs)).append('|');
    for (int i = 0; i < declared.size(); i++) {
      if (i > 0) sb.append(';');
      sb.append(declared.get(i).encode());
    }
    if (!ranges.isEmpty()) {
      sb.append('|');
      for (int i = 0; i < ranges.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(ranges.get(i).start()).append('-').append(ranges.get(i).end());
      }
    }
    return sb.toString();
  }

  public static Schema decode(String s) {
    List<String> oneofs = new ArrayList<>();
    int bar = s.indexOf('|');
    if (bar >= 0) {
      String o = s.substring(0, bar);
      if (!o.isEmpty()) oneofs.addAll(List.of(o.split(",")));
      s = s.substring(bar + 1);
    }
    List<Range> ranges = new ArrayList<>();
    int bar2 = s.indexOf('|');
    if (bar2 >= 0) {
      for (String r : s.substring(bar2 + 1).split(",")) {
        int dash = r.indexOf('-');
        ranges.add(new Range(Integer.parseInt(r.substring(0, dash)), Integer.parseInt(r.substring(dash + 1))));
      }
      s = s.substring(0, bar2);
    }
    List<Field> out = new ArrayList<>();
    if (!s.isEmpty()) {
      for (String part : s.split(";")) out.add(Field.decode(part));
    }
    return new Schema(out, oneofs, ranges);
  }
}
