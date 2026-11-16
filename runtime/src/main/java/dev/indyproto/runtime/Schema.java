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
    MESSAGE('L', "Message", 2, ListKind.OBJECT);

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
      String typeInternalName) {

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

    /** JVM type of the storage field (same on message and builder). */
    public ClassDesc storageDesc() {
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

    public int tag() {
      return (number << 3) | kind.wireType;
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
      return number + ":" + kind.name() + ":" + f + ":" + bit + ":" + camelName + ":"
          + (typeInternalName == null ? "" : typeInternalName);
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
          p[5].isEmpty() ? null : p[5]);
    }
  }

  private final List<Field> fields;

  public Schema(List<Field> fields) {
    List<Field> sorted = new ArrayList<>(fields);
    sorted.sort(Comparator.comparingInt(Field::number));
    this.fields = Collections.unmodifiableList(sorted);
  }

  public List<Field> fields() {
    return fields;
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
    StringBuilder sb = new StringBuilder();
    for (Field f : fields) {
      if (!sb.isEmpty()) sb.append(';');
      sb.append(f.encode());
    }
    return sb.toString();
  }

  public static Schema decode(String s) {
    List<Field> out = new ArrayList<>();
    if (!s.isEmpty()) {
      for (String part : s.split(";")) out.add(Field.decode(part));
    }
    return new Schema(out);
  }
}
