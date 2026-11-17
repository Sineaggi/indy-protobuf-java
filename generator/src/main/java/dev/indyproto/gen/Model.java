package dev.indyproto.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.indyproto.runtime.CD;
import dev.indyproto.runtime.Schema;
import dev.indyproto.runtime.Schema.Kind;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;

/** Per-message view of the descriptor, with everything the class writers need. */
final class Model {

  /** A real (non-synthetic) oneof: storage {@code lower_} / {@code lowerCase_}, enum {@code CapCase}. */
  record O(int index, String protoName, String cap, String lower, ClassDesc caseEnum,
      List<F> members) {
    String caseField() { return lower + "Case_"; }
    String valueField() { return lower + "_"; }
    String notSet() { return protoName.toUpperCase(java.util.Locale.ROOT) + "_NOT_SET"; }
  }

  /**
   * A field plus the class names it refers to. For map fields, {@code type} and friends describe
   * the value (message or enum), {@code openEnum} whether an enum value is open.
   */
  record F(Schema.Field s, FieldDescriptor fd, ClassDesc type, ClassDesc typeBuilder,
      ClassDesc typeOrBuilder, boolean openEnum) {

    boolean isMap() { return s.isMap(); }
    boolean inOneof() { return s.inOneof(); }

    String cap() { return s.camelName(); }
    String field() { return s.fieldName(); }
    String fb() { return s.builderFieldName(); }
    Kind kind() { return s.kind(); }
    boolean repeated() { return s.repeated(); }
    boolean presence() { return s.presence(); }
    ClassDesc storage() { return s.storageDesc(); }
  }

  final Descriptor d;
  final ClassDesc msg;
  final ClassDesc builder;
  final ClassDesc orBuilder;
  final List<F> fields = new ArrayList<>();
  final List<O> oneofs = new ArrayList<>();
  /** CamelCase names of all oneofs, synthetic ones included (FieldAccessorTable wants them). */
  final List<String> allOneofNames = new ArrayList<>();
  final Schema schema;

  Model(Descriptor d, Names names) {
    this.d = d;
    this.msg = names.message(d);
    this.builder = names.builder(d);
    this.orBuilder = names.orBuilder(d);
    if (d.getOptions().getMessageSetWireFormat()) {
      throw new UnsupportedOperationException("prototype does not support message_set_wire_format (" + d.getFullName() + ")");
    }
    int bit = 0;
    List<Schema.Field> sf = new ArrayList<>();
    List<String> oneofNames = new ArrayList<>();
    for (var od : d.getOneofs()) allOneofNames.add(Names.camel(od.getName(), true));
    for (var od : d.getRealOneofs()) {
      String cap = Names.camel(od.getName(), true);
      oneofNames.add(Names.camel(od.getName(), false));
      oneofs.add(new O(oneofs.size(), od.getName(), cap, Names.camel(od.getName(), false),
          ClassDesc.of(Names.internal(msg).replace('/', '.') + "$" + cap + "Case"),
          new ArrayList<>()));
    }
    for (FieldDescriptor fd : d.getFields()) {
      check(fd);
      var real = fd.getRealContainingOneof();
      int oneof = real == null ? -1 : d.getRealOneofs().indexOf(real);
      if (fd.isMapField()) {
        FieldDescriptor kf = fd.getMessageType().findFieldByNumber(1);
        FieldDescriptor vf = fd.getMessageType().findFieldByNumber(2);
        Kind vk = kindOf(vf);
        ClassDesc type = null, tb = null, tob = null;
        boolean closed = false;
        if (vk == Kind.MESSAGE) {
          ClassDesc[] t = messageTypes(vf, names);
          type = t[0]; tb = t[1]; tob = t[2];
        } else if (vk == Kind.ENUM) {
          type = names.enumClass(vf.getEnumType());
          closed = vf.legacyEnumFieldTreatedAsClosed();
        }
        Schema.Field f = new Schema.Field(fd.getNumber(), Kind.MAP, false, false, false, false,
            closed, -1, Names.camel(fd.getName(), true),
            type == null ? null : Names.internal(type), -1, kindOf(kf), vk);
        sf.add(f);
        fields.add(new F(f, fd, type, tb, tob, vk == Kind.ENUM && !closed));
        continue;
      }
      Kind kind = kindOf(fd);
      boolean repeated = fd.isRepeated();
      boolean presence = !repeated && oneof < 0 && fd.hasPresence();
      boolean closed = kind == Kind.ENUM && fd.legacyEnumFieldTreatedAsClosed();
      ClassDesc type = null, tb = null, tob = null;
      if (kind == Kind.MESSAGE) {
        ClassDesc[] t = messageTypes(fd, names);
        type = t[0]; tb = t[1]; tob = t[2];
      } else if (kind == Kind.ENUM) {
        type = names.enumClass(fd.getEnumType());
      }
      boolean group = fd.getType() == FieldDescriptor.Type.GROUP;
      boolean customDefault = !repeated && kind != Kind.MESSAGE && fd.hasDefaultValue();
      Schema.Field f = new Schema.Field(
          fd.getNumber(), kind, repeated, repeated && fd.isPacked(), presence,
          kind == Kind.STRING && fd.needsUtf8Check(), closed,
          presence ? bit++ : -1,
          Names.camel(fieldName(fd), true),
          type == null ? null : Names.internal(type), oneof, null, null, group, customDefault);
      sf.add(f);
      F mf = new F(f, fd, type, tb, tob, kind == Kind.ENUM && !closed);
      fields.add(mf);
      if (oneof >= 0) oneofs.get(oneof).members().add(mf);
    }
    List<Schema.Range> ranges = new ArrayList<>();
    for (var r : d.toProto().getExtensionRangeList()) ranges.add(new Schema.Range(r.getStart(), r.getEnd()));
    this.schema = new Schema(sf, oneofNames, ranges);
  }

  /** {message, builder, orBuilder} of a message-typed field; well-known types are stock classes. */
  private static ClassDesc[] messageTypes(FieldDescriptor fd, Names names) {
    ClassDesc type = names.message(fd.getMessageType());
    ClassDesc tb = names.builder(fd.getMessageType());
    ClassDesc tob = names.orBuilder(fd.getMessageType());
    if (names.isWellKnown(fd.getMessageType().getFile())) {
      tb = ClassDesc.of(Names.internal(type).replace('/', '.') + "$Builder");
      tob = ClassDesc.of(Names.internal(type).replace('/', '.') + "OrBuilder");
    }
    return new ClassDesc[] {type, tb, tob};
  }

  /** protoc's FieldName(): a group's accessors keep the capitalization of its type name. */
  static String fieldName(FieldDescriptor fd) {
    return fd.getType() == FieldDescriptor.Type.GROUP ? fd.getMessageType().getName() : fd.getName();
  }

  /** Declares extension ranges: ExtendableMessage / ExtendableBuilder. */
  boolean extendable() {
    return !d.toProto().getExtensionRangeList().isEmpty();
  }

  boolean hasMaps() {
    for (F f : fields) if (f.isMap()) return true;
    return false;
  }

  O oneof(F f) {
    return oneofs.get(f.s().oneof());
  }

  private static void check(FieldDescriptor fd) {
    String why = null;
    if (why != null) {
      throw new UnsupportedOperationException(
          "prototype does not support " + why + " (" + fd.getFullName() + ")");
    }
  }

  static Kind kindOf(FieldDescriptor fd) {
    return switch (fd.getType()) {
      case INT32 -> Kind.INT32;
      case INT64 -> Kind.INT64;
      case UINT32 -> Kind.UINT32;
      case UINT64 -> Kind.UINT64;
      case SINT32 -> Kind.SINT32;
      case SINT64 -> Kind.SINT64;
      case FIXED32 -> Kind.FIXED32;
      case FIXED64 -> Kind.FIXED64;
      case SFIXED32 -> Kind.SFIXED32;
      case SFIXED64 -> Kind.SFIXED64;
      case FLOAT -> Kind.FLOAT;
      case DOUBLE -> Kind.DOUBLE;
      case BOOL -> Kind.BOOL;
      case ENUM -> Kind.ENUM;
      case STRING -> Kind.STRING;
      case BYTES -> Kind.BYTES;
      case MESSAGE, GROUP -> Kind.MESSAGE;
    };
  }

  /** Java type of a single element / value as seen by users (getFoo / setFoo). */
  static ClassDesc valueType(F f) {
    return switch (f.kind()) {
      case STRING -> CD.STRING;
      case BYTES -> CD.BYTE_STRING;
      case MESSAGE, ENUM -> f.type();
      default -> f.kind().valueDesc();
    };
  }

  static ClassDesc boxed(ClassDesc prim) {
    return switch (prim.descriptorString()) {
      case "I" -> ClassDesc.of("java.lang.Integer");
      case "J" -> ClassDesc.of("java.lang.Long");
      case "F" -> ClassDesc.of("java.lang.Float");
      case "D" -> ClassDesc.of("java.lang.Double");
      case "Z" -> ClassDesc.of("java.lang.Boolean");
      default -> prim;
    };
  }
}
